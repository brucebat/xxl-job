package com.xxl.job.core.executor.impl;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.glue.GlueFactory;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.handler.impl.MethodJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Map;


/**
 * xxl-job executor (for spring)
 *
 * @author xuxueli 2018-11-01 09:24:52
 */
public class XxlJobSpringExecutor extends XxlJobExecutor implements ApplicationContextAware, SmartInitializingSingleton, DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(XxlJobSpringExecutor.class);

    /**
     * 开始
     */
    @Override
    public void afterSingletonsInstantiated() {

        // 从方法级别进行任务处理者仓库初始化，这里进行了优化，将任务的粒度由原先的类转变为了方法级别
        initJobHandlerMethodRepository(applicationContext);

        // 刷新GlueFactory，此处获取SpringGlueFactory
        GlueFactory.refreshInstance(1);

        // super start
        try {
            super.start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * 销毁
     */
    @Override
    public void destroy() {
        super.destroy();
    }

    private void initJobHandlerMethodRepository(ApplicationContext applicationContext) {
        if (applicationContext == null) {
            return;
        }
        // 进行任务处理程序初始化
        // 获取bean名称列表
        String[] beanDefinitionNames = applicationContext.getBeanNamesForType(Object.class, false, true);
        for (String beanDefinitionName : beanDefinitionNames) {
            // 从上下文中根据bean元数据名称获取bean对象
            Object bean = applicationContext.getBean(beanDefinitionName);
            // referred to ：org.springframework.context.event.EventListenerMethodProcessor.processBean
            // 获取被@XxlJob注解的方法
            Map<Method, XxlJob> annotatedMethods = null;
            try {
                // 根据bean类元信息获取被@XxlJob注解的方法
                annotatedMethods = MethodIntrospector.selectMethods(bean.getClass(),
                        new MethodIntrospector.MetadataLookup<XxlJob>() {
                            @Override
                            public XxlJob inspect(Method method) {
                                return AnnotatedElementUtils.findMergedAnnotation(method, XxlJob.class);
                            }
                        });
            } catch (Throwable ex) {
                logger.error("xxl-job method-jobhandler resolve error for bean[" + beanDefinitionName + "].", ex);
            }
            if (annotatedMethods == null || annotatedMethods.isEmpty()) {
                continue;
            }

            for (Map.Entry<Method, XxlJob> methodXxlJobEntry : annotatedMethods.entrySet()) {
                // 被注解的方法，即具体的任务
                Method method = methodXxlJobEntry.getKey();
                // XxlJob注解类，获取设置的注解信息
                XxlJob xxlJob = methodXxlJobEntry.getValue();
                if (xxlJob == null) {
                    continue;
                }
                // 任务名称
                String name = xxlJob.value();
                if (name.trim().length() == 0) {
                    throw new RuntimeException("xxl-job method-jobhandler name invalid, for[" + bean.getClass() + "#" + method.getName() + "] .");
                }
                // 检查当前任务名是否已经被使用
                if (loadJobHandler(name) != null) {
                    throw new RuntimeException("xxl-job jobhandler[" + name + "] naming conflicts.");
                }

                // 进行方法参数类型数量和参数类型判断，这里要求方法的参数列表只能有一个参数，并且参数类型为String
                if (!(method.getParameterTypes().length == 1 && method.getParameterTypes()[0].isAssignableFrom(String.class))) {
                    throw new RuntimeException("xxl-job method-jobhandler param-classtype invalid, for[" + bean.getClass() + "#" + method.getName() + "] , " +
                            "The correct method format like \" public ReturnT<String> execute(String param) \" .");
                }
                // 判断方法返回参数类型是否为ReturnT，如果不为ReturnT则为非法
                if (!method.getReturnType().isAssignableFrom(ReturnT.class)) {
                    throw new RuntimeException("xxl-job method-jobhandler return-classtype invalid, for[" + bean.getClass() + "#" + method.getName() + "] , " +
                            "The correct method format like \" public ReturnT<String> execute(String param) \" .");
                }
                method.setAccessible(true);

                // 设置初始化方法和销毁方法
                Method initMethod = null;
                Method destroyMethod = null;

                if (xxlJob.init().trim().length() > 0) {
                    try {
                        initMethod = bean.getClass().getDeclaredMethod(xxlJob.init());
                        initMethod.setAccessible(true);
                    } catch (NoSuchMethodException e) {
                        throw new RuntimeException("xxl-job method-jobhandler initMethod invalid, for[" + bean.getClass() + "#" + method.getName() + "] .");
                    }
                }
                if (xxlJob.destroy().trim().length() > 0) {
                    try {
                        destroyMethod = bean.getClass().getDeclaredMethod(xxlJob.destroy());
                        destroyMethod.setAccessible(true);
                    } catch (NoSuchMethodException e) {
                        throw new RuntimeException("xxl-job method-jobhandler destroyMethod invalid, for[" + bean.getClass() + "#" + method.getName() + "] .");
                    }
                }

                // 进行任务处理程序注册
                registJobHandler(name, new MethodJobHandler(bean, method, initMethod, destroyMethod));
            }
        }

    }


    /**
     * 应用上下文
     */
    private static ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }

}
