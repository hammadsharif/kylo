package com.thinkbiganalytics.nifi.provenance.model;

import com.thinkbiganalytics.nifi.provenance.model.stats.AggregatedProcessorStatistics;
import com.thinkbiganalytics.nifi.provenance.model.stats.ProvenanceEventStats;
import com.thinkbiganalytics.nifi.provenance.model.util.ProvenanceEventUtil;

import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Determines a Stream by looking at the last {n} events and determines if the time between each of the events is within a given streaming threshold.
 *
 * If the event is detected as being a stream but the originating flow file event ( the one that started the job) was declared a Batch event then it will also process it
 *
 * Created by sr186054 on 8/25/16.
 */
public class GroupedFeedProcessorEventAggregate implements Serializable {

    private static final Logger log = LoggerFactory.getLogger(GroupedFeedProcessorEventAggregate.class);

    private String feedName;
    private String processorId;
    private String processorName;
    private DateTime lastEventTime;
    private DateTime lastSystemTime;


    private List<ProvenanceEventRecordDTO> potentialStreamEvents = new ArrayList<>();
    private List<ProvenanceEventRecordDTO> jmsEvents = new ArrayList<>();
    private List<ProvenanceEventRecordDTO> streamEvents = new ArrayList<>();
    private Map<String, ProvenanceEventRecordDTO> lastStreamEventByJob = new ConcurrentHashMap<>();


    private ProvenanceEventRecordDTO lastEvent;
    private AggregatedProcessorStatistics stats;

    private Long allowedMillisBetweenEvents;

    private AtomicInteger eventCount = new AtomicInteger(0);
    private AtomicInteger batchCount = new AtomicInteger(0);
    private AtomicInteger streamingCount = new AtomicInteger(0);

    private AtomicInteger tempStreamingCount = new AtomicInteger(0);

    private List<ProvenanceEventRecordDTO> failedEvents;
    private DateTime initTime;

    private Integer numberOfEventsThatMakeAStream;

    private final ReentrantLock lock = new ReentrantLock(true);

    public GroupedFeedProcessorEventAggregate(String feedName, String processorId, Long allowedMillisBetweenEvents, Integer numberOfEventsThatMakeAStream) {

        this.feedName = feedName;
        this.processorId = processorId;
        this.failedEvents = new LinkedList<>();
        this.allowedMillisBetweenEvents = allowedMillisBetweenEvents;
        this.numberOfEventsThatMakeAStream = numberOfEventsThatMakeAStream;
        this.stats = new AggregatedProcessorStatistics(processorId, feedName);
        this.initTime = DateTime.now();
        this.lastSystemTime = DateTime.now();
        log.info("new FeedProcessorEventAggregate for " + feedName + "," + processorId + " - " + this.initTime);
    }


    public GroupedFeedProcessorEventAggregate addEvent(ProvenanceEventRecordDTO event, ProvenanceEventStats stats) {
        groupEventAsStreamOrBatch(event, stats);
        lastSystemTime = DateTime.now();
        lastEventTime = event.getEventTime();
        lastEvent = event;
        return this;
    }

    private String lastStreamEventMapKey(ProvenanceEventRecordDTO event) {
        return event.getJobFlowFileId() + "_" + event.getEventType() + "_" + event.isEndOfJob() + "_" + event.isStartOfJob();
    }

    private void movePotentialStreamToBatch() {
        if (!potentialStreamEvents.isEmpty()) {
            log.info("Marking {} as batch ", potentialStreamEvents.size());
            markFirstEventsAsBatch(potentialStreamEvents);
            jmsEvents.addAll(potentialStreamEvents);
            batchCount.addAndGet(potentialStreamEvents.size());
            potentialStreamEvents.clear();
        }
    }

    private void movePotentialStreamToStream() {
        if (!potentialStreamEvents.isEmpty()) {
            potentialStreamEvents.stream().forEach(e -> {
                e.setStream(true);
                if (e.isStartOfJob()) {
                    e.getFlowFile().getRootFlowFile().setFirstEventType(RootFlowFile.FIRST_EVENT_TYPE.STREAM);
                }
                lastStreamEventByJob.put(lastStreamEventMapKey(e), e);
            });
            streamEvents.addAll(potentialStreamEvents);
            streamingCount.addAndGet(potentialStreamEvents.size());
            potentialStreamEvents.clear();
        }
    }

    private void moveToStream(ProvenanceEventRecordDTO event) {
        event.setStream(true);
        if (event.isStartOfJob()) {
            event.getFlowFile().getRootFlowFile().setFirstEventType(RootFlowFile.FIRST_EVENT_TYPE.STREAM);
        }
        lastStreamEventByJob.put(lastStreamEventMapKey(event), event);
        streamEvents.add(event);
        streamingCount.incrementAndGet();
    }


    private void groupEventAsStreamOrBatch(ProvenanceEventRecordDTO event, ProvenanceEventStats stats) {
        lock.lock();
        try {
            if (lastEventTime == null) {
                lastEventTime = event.getEventTime();
            }
            if (ProvenanceEventUtil.isCompletionEvent(event)) {
                eventCount.incrementAndGet();
                checkAndMarkAsEndOfJob(event, stats);
                //if the event is not the first event, but the first event is a Stream then move this to a stream
                if (RootFlowFile.FIRST_EVENT_TYPE.STREAM.equals(event.getFlowFile().getRootFlowFile().getFirstEventType())) {
                    moveToStream(event);

                } else {
                if (event.getEventTime().isBefore(lastEventTime.plus(allowedMillisBetweenEvents))) {
                    if (tempStreamingCount.incrementAndGet() >= numberOfEventsThatMakeAStream) {
                        movePotentialStreamToStream();
                        moveToStream(event);
                        //checkAndAddJobCompletionEvents(event,stats,streamEvents);
                    } else {
                        potentialStreamEvents.add(event);
                        // checkAndAddJobCompletionEvents(event,stats,potentialStreamEvents);

                    }
                } else {
                    potentialStreamEvents.add(event);
                    //  checkAndAddJobCompletionEvents(event,stats,potentialStreamEvents);
                    /// no longer a stream event
                    movePotentialStreamToBatch();
                    tempStreamingCount.set(0);
                }
                }
            } else {
                log.info("Non completion event {} ", event);
            }
        } finally {
            lock.unlock();
        }
    }

    private void checkAndMarkAsEndOfJob(ProvenanceEventRecordDTO event, ProvenanceEventStats stats) {
        if (stats.getJobsFinished() == 1L && !event.getFlowFile().isRootFlowFile()) {
            log.info("Marking {} as the end of the job for {}.  is already end of job? {} ", event.getEventId(), event.getFlowFile().getRootFlowFile().getId(), event.isEndOfJob());
            event.setIsEndOfJob(true);
        }
    }

    private void checkAndAddJobCompletionEvents(ProvenanceEventRecordDTO event, ProvenanceEventStats stats, List<ProvenanceEventRecordDTO> list) {
        //if the event ended the job, but it is not the root flow file then an additional event needs to be sent off to the jms queue indicating the job is complete
        if (stats.getJobsFinished() == 1L && !event.getFlowFile().isRootFlowFile()) {
            event.setIsEndOfJob(true);
            // event.getFlowFile().getRootFlowFile().getLastEvent().setIsEndOfJob(true);
            //  list.add(event.getFlowFile().getRootFlowFile().getLastEvent());
            log.info("Adding the additional Completion Event {}  completing {} Root type: {} ", event.getFlowFile().getRootFlowFile().getLastEvent().getEventId(),
                     event.getFlowFile().getRootFlowFile().getId(), event.getFlowFile().getRootFlowFile().getFirstEventType());
        }
    }

    private List<ProvenanceEventRecordDTO> addStreamingEventsWhoseFirstEventWasABatchToQueue() {
        // each job does not need all events.  they just need the start and ending events in this batch relative to the jobflowfileid

        //  List<ProvenanceEventRecordDTO> events = streamEvents.stream().filter(e -> ActiveFlowFile.FIRST_EVENT_TYPE.BATCH.equals(e.getFlowFile().getRootFlowFile().getFirstEventType())).collect(Collectors.toList());
        List<ProvenanceEventRecordDTO> events = lastStreamEventByJob.values().stream().filter(
            e -> RootFlowFile.FIRST_EVENT_TYPE.BATCH.equals(e.getFlowFile().getRootFlowFile().getFirstEventType())).collect(Collectors.toList());
        if (events != null && !events.isEmpty()) {
            jmsEvents.addAll(events);
            log.info("Adding {} events for feed/processor {}/{}({}) since they originated from a BATCH event ", events.size(), feedName, processorName, processorId);
        }
        return events;
    }

    private void markFirstEventsAsBatch(List<ProvenanceEventRecordDTO> events) {
        events.stream().filter(e -> e.isStartOfJob()).map(e -> e.getFlowFile().getRootFlowFile()).forEach(ff -> ff.setFirstEventType(RootFlowFile.FIRST_EVENT_TYPE.BATCH));
    }

    private void printList(List<ProvenanceEventRecordDTO> list, String title) {
        log.info("Print {} ", title);
        for (ProvenanceEventRecordDTO e : list) {
            log.info("Event {} - {} ", e.getEventId(), e.getEventTime());
        }
    }

    public List<ProvenanceEventRecordDTO> collectEventsToBeSentToJmsQueue() {
        lock.lock();
        List<ProvenanceEventRecordDTO> events = null;
        try {
            //Move anything that is potential to batch if the time between is greater
            if (DateTime.now().isAfter(lastEventTime.plus(allowedMillisBetweenEvents))) {
                movePotentialStreamToBatch();
            }
            //if the First Event was a Batch event we should pass this event through so it gets reconciled in the Ops Manager
            addStreamingEventsWhoseFirstEventWasABatchToQueue();
            //copy and clear
            log.info("copy and clear {}, Events: {}  ", jmsEvents.size(), StringUtils.join(jmsEvents.stream().map(e -> e.getEventId()).collect(Collectors.toList()), ","));

            events = new ArrayList<>(jmsEvents);
            jmsEvents.clear();
            //add in any Streaming events to the copied events array
            streamEvents.clear();
            lastStreamEventByJob.clear();
        } finally {
            lock.unlock();
        }

        return events == null ? new ArrayList<>() : events;

    }



    public void add(ProvenanceEventStats stats, ProvenanceEventRecordDTO event) {
        if (event.getComponentName() != null && processorName == null) {
            processorName = event.getComponentName();
        }
        addEvent(event, stats);
        addEventStats(stats);


    }

    private GroupedFeedProcessorEventAggregate addEventStats(ProvenanceEventStats stats) {
        if (stats != null) {
            this.stats.add(stats);
        }
        return this;
    }

    public String getFeedName() {
        return feedName;
    }

    public void setFeedName(String feedName) {
        this.feedName = feedName;
    }

    public String getProcessorId() {
        return processorId;
    }

    public void setProcessorId(String processorId) {
        this.processorId = processorId;
    }

    public String getProcessorName() {
        return processorName;
    }

    public void setProcessorName(String processorName) {
        this.processorName = processorName;
    }

    public DateTime getLastEventTime() {
        return lastEventTime;
    }

    public void setLastEventTime(DateTime lastEventTime) {
        this.lastEventTime = lastEventTime;
    }


    public AggregatedProcessorStatistics getStats() {
        return stats;
    }

    public void setStats(AggregatedProcessorStatistics stats) {
        this.stats = stats;
    }


    public String getSummary() {
        return stats.toString();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("FeedProcessorEventAggregate{");
        sb.append("feedName='").append(feedName).append('\'');
        sb.append(", processorId='").append(processorId).append('\'');
        sb.append(", summary='").append(getSummary()).append('\'');
        sb.append('}');
        return sb.toString();
    }
}