package com.thinkbiganalytics.metadata.sla;

import com.thinkbiganalytics.alerts.api.Alert;
import com.thinkbiganalytics.classnameregistry.ClassNameChange;
import com.thinkbiganalytics.metadata.sla.alerts.ServiceLevelAssessmentAlertUtil;
import com.thinkbiganalytics.metadata.sla.api.ServiceLevelAgreementAction;
import com.thinkbiganalytics.metadata.sla.api.ServiceLevelAgreementActionValidation;
import com.thinkbiganalytics.metadata.sla.api.ServiceLevelAssessment;

import javax.inject.Inject;

/**
 * Email the users specified in the incoming Configuration class about the SLA violation
 *
 */
@ClassNameChange(classNames = {"com.thinkbiganalytics.metadata.sla.alerts.EmailServiceLevelAgreementAction"})
public class EmailServiceLevelAgreementAction implements ServiceLevelAgreementAction<EmailServiceLevelAgreementActionConfiguration> {

    @Inject
    private SlaEmailService emailService;

    @Override
    public boolean respond(EmailServiceLevelAgreementActionConfiguration actionConfiguration, ServiceLevelAssessment assessment, Alert a) {
        String desc = ServiceLevelAssessmentAlertUtil.getDescription(assessment);
        String slaName = assessment.getAgreement().getName();
        String email = actionConfiguration.getEmailAddresses();
        //mail it
        emailService.sendMail(email, "SLA Violated: " + slaName, desc);

        return true;
    }

    public ServiceLevelAgreementActionValidation validateConfiguration(){

        if(emailService.isConfigured()){
            return ServiceLevelAgreementActionValidation.VALID;
        }
        else {
            return new ServiceLevelAgreementActionValidation(false,"Email connection information is not setup.  Please contact an administrator to set this up.");
        }

        /*
        try {
            emailService.testConnection();
            return ServiceLevelAgreementActionValidation.VALID;
        }catch (MessagingException e){
            return new ServiceLevelAgreementActionValidation(false,e.getMessage());
        }
        */
    }

}