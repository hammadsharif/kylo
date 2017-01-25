package com.thinkbiganalytics.nifi.provenance.util;

import com.google.common.base.CaseFormat;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Utility to get access to the Spring Application Context and the Spring Beans
 */
public class SpringApplicationContext {

    private static final Logger log = LoggerFactory.getLogger(SpringApplicationContext.class);

    private static class LazyHolder {

        static final SpringApplicationContext INSTANCE = new SpringApplicationContext();
    }

    public static SpringApplicationContext getInstance() {
        return LazyHolder.INSTANCE;
    }

    private static ApplicationContext applicationContext;

    private AtomicBoolean initialized = new AtomicBoolean(false);


    public void initializeSpring() {
        initializeSpring("application-context.xml");
    }

    public void initializeSpring(String configFileName) {
        if (initialized.compareAndSet(false, true)) {
            log.info("Initializing Spring with {} ", configFileName);
            this.applicationContext = new ClassPathXmlApplicationContext();
            ((ClassPathXmlApplicationContext) this.applicationContext).setClassLoader(getClass().getClassLoader());
            ((ClassPathXmlApplicationContext) this.applicationContext).setConfigLocation(configFileName);
            ((ClassPathXmlApplicationContext) this.applicationContext).refresh();
        }
    }


    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    /**
     * get a Spring bean by name
     */
    public Object getBean(String beanName) throws BeansException {
        if (applicationContext == null) {
            //  initializeSpring();
            log.error("Attempt to get bean {}, but appcontext is null ", beanName);
        }
        if (applicationContext != null) {
            try {
                return this.applicationContext.getBean(beanName);
            } catch (Exception e) {
                log.error("Error getting bean {} , {} ", beanName, e.getMessage(), e);
            }
        } else {
            log.error("Unable to get Spring bean for {}.  Application Context is null", beanName);
        }
        return null;

    }


    /**
     * get a Spring bean by the Class type
     * @param requiredType
     * @param <T>
     * @return
     * @throws BeansException
     */
    public <T> T getBean(Class<T> requiredType) throws BeansException {
        T bean = null;
        try {
            bean = this.applicationContext.getBean(requiredType);
        } catch (Exception e) {
            //try to find it by the name
            String name = requiredType.getSimpleName();
            name = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, name);
            bean = (T) getBean(name);
        }
        return bean;
    }


    /**
     * get a bean by the name and its type
     * @param name
     * @param requiredType
     * @param <T>
     * @return
     * @throws BeansException
     */
    public <T> T getBean(String name, Class<T> requiredType) throws BeansException {
        return this.applicationContext.getBean(name, requiredType);
    }


    /**
     * print out the bean names in Spring
     * @return
     */
    public String printBeanNames() {
        if (applicationContext == null) {
            initializeSpring();
        }
        if (applicationContext != null) {
            String beanNames = StringUtils.join(applicationContext.getBeanDefinitionNames(), ",");
            log.info("SPRING BEANS: {}", beanNames);
            return beanNames;
        } else {
            log.error("Unable to print the spring bean names.  The application context is null");
            return null;
        }
    }

    /**
     * Autowire properties in the object
     * @param key the name of the Spring Bean
     * @param obj the Bean or Object you want to be included into Spring and autowired
     * @return the autowired Spring object
     */
    public Object autowire(String key, Object obj) {

        return autowire(key, obj, true);
    }


    /**
     * Autowire an object
     * @param key the name of the Spring Bean
     * @param obj the Bean or Object you want to be included into Spring and autowired
     * @param force Force it to be autowired even if the bean is not registered with the appcontext.  If the key is already registered in spring and this is false it will not autowire.
     * @return the autowired Spring object
     */
    public Object autowire(String key, Object obj, boolean force) {
        Object bean = null;
        try {
            bean = SpringApplicationContext.getInstance().getBean(key);
        } catch (Exception e) {

        }
        if (bean == null || force) {

            try {
                if (applicationContext == null) {
                    initializeSpring();
                }
                if (applicationContext != null) {
                    AutowireCapableBeanFactory autowire = getApplicationContext().getAutowireCapableBeanFactory();
                    autowire.autowireBean(obj);
                    //fire PostConstruct methods
                    autowire.initializeBean(obj, key);
                    return obj;
                } else {
                    log.error("Unable to autowire {} with Object: {}.  ApplicationContext is null.", key, obj);
                }
            } catch (Exception e) {
                log.error("Unable to autowire {} with Object: {} ", key, obj);
            }
        } else if (bean != null) {
            return bean;
        }
        return null;
    }
}
