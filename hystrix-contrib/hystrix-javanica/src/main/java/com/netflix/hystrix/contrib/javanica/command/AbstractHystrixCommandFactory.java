/**
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.contrib.javanica.command;


import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.netflix.hystrix.HystrixCollapser;
import com.netflix.hystrix.HystrixThreadPoolProperties;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixProperty;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Base implementation of {@link HystrixCommandFactory} interface.
 *
 * @param <T> the type of Hystrix command
 */
public abstract class AbstractHystrixCommandFactory<T extends AbstractHystrixCommand>
        implements HystrixCommandFactory<T> {

    /**
     * {@inheritDoc}
     */
    @Override
    public T create(MetaHolder metaHolder,
                    Collection<HystrixCollapser.CollapsedRequest<Object, Object>> collapsedRequests) {
        Validate.notNull(metaHolder.getHystrixCommand(), "hystrixCommand cannot be null");
        String groupKey = StringUtils.isNotEmpty(metaHolder.getHystrixCommand().groupKey()) ?
                metaHolder.getHystrixCommand().groupKey()
                : metaHolder.getDefaultGroupKey();
        String commandKey = StringUtils.isNotEmpty(metaHolder.getHystrixCommand().commandKey()) ?
                metaHolder.getHystrixCommand().commandKey()
                : metaHolder.getDefaultCommandKey();

        HystrixThreadPoolProperties.Setter threadPoolProperties = getThreadPoolProperties(metaHolder.getHystrixCommand());

        CommandSetterBuilder setterBuilder = new CommandSetterBuilder();
        setterBuilder.commandKey(commandKey);
        setterBuilder.groupKey(groupKey);
        setterBuilder.threadPoolKey(metaHolder.getHystrixCommand().threadPoolKey());
        if(threadPoolProperties != null) {
            setterBuilder.threadPoolProperties(threadPoolProperties);
        }
        Map<String, Object> commandProperties = getCommandProperties(metaHolder.getHystrixCommand());
        CommandAction commandAction = new MethodExecutionAction(metaHolder.getObj(), metaHolder.getMethod(), metaHolder.getArgs());
        CommandAction fallbackAction = createFallbackAction(metaHolder, collapsedRequests);
        CommandAction cacheKeyAction = createCacheKeyAction(metaHolder);
        CommandActions commandActions = CommandActions.builder().commandAction(commandAction)
                .fallbackAction(fallbackAction).cacheKeyAction(cacheKeyAction).build();
        return create(setterBuilder, commandActions, commandProperties, collapsedRequests,
                metaHolder.getHystrixCommand().ignoreExceptions(), metaHolder.getExecutionType());
    }

    CommandAction createFallbackAction(MetaHolder metaHolder,
                                       Collection<HystrixCollapser.CollapsedRequest<Object, Object>> collapsedRequests) {
        String fallbackMethodName = metaHolder.getHystrixCommand().fallbackMethod();
        CommandAction fallbackAction = null;
        if (StringUtils.isNotEmpty(fallbackMethodName)) {
            try {
                Method fallbackMethod = metaHolder.getObj().getClass()
                        .getDeclaredMethod(fallbackMethodName, metaHolder.getParameterTypes());
                if (fallbackMethod.isAnnotationPresent(HystrixCommand.class)) {
                    fallbackMethod.setAccessible(true);
                    MetaHolder fmMetaHolder = MetaHolder.builder()
                            .obj(metaHolder.getObj())
                            .method(fallbackMethod)
                            .args(metaHolder.getArgs())
                            .defaultCollapserKey(metaHolder.getDefaultCollapserKey())
                            .defaultCommandKey(fallbackMethod.getName())
                            .defaultGroupKey(metaHolder.getDefaultGroupKey())
                            .hystrixCollapser(metaHolder.getHystrixCollapser())
                            .hystrixCommand(fallbackMethod.getAnnotation(HystrixCommand.class)).build();
                    fallbackAction = new LazyCommandExecutionAction(GenericHystrixCommandFactory.getInstance(), fmMetaHolder, collapsedRequests);
                } else {
                    fallbackAction = new MethodExecutionAction(metaHolder.getObj(), fallbackMethod, metaHolder.getArgs());
                }
            } catch (NoSuchMethodException e) {
                throw Throwables.propagate(e);
            }
        }
        return fallbackAction;
    }

    abstract T create(CommandSetterBuilder setterBuilder,
                      CommandActions commandActions,
                      Map<String, Object> commandProperties,
                      Collection<HystrixCollapser.CollapsedRequest<Object, Object>> collapsedRequests,
                      Class<? extends Throwable>[] ignoreExceptions,
                      ExecutionType executionType);


    private CommandAction createCacheKeyAction(MetaHolder metaHolder) {
        CommandAction cacheKeyAction = null;
        if (metaHolder.getCacheKeyMethod() != null) {
            cacheKeyAction = new MethodExecutionAction(metaHolder.getObj(), metaHolder.getCacheKeyMethod(), metaHolder.getArgs());
        }
        return cacheKeyAction;
    }

    private Map<String, Object> getCommandProperties(HystrixCommand hystrixCommand) {
        if (hystrixCommand.commandProperties() == null || hystrixCommand.commandProperties().length == 0) {
            return Collections.emptyMap();
        }
        Map<String, Object> commandProperties = Maps.newHashMap();
        for (HystrixProperty commandProperty : hystrixCommand.commandProperties()) {
            commandProperties.put(commandProperty.name(), commandProperty.value());
        }
        return commandProperties;
    }

    public HystrixThreadPoolProperties.Setter getThreadPoolProperties(HystrixCommand hystrixCommand) {
        if(hystrixCommand.threadPoolProperties() == null || hystrixCommand.threadPoolProperties().length == 0) {
            return null;
        }

        HystrixThreadPoolProperties.Setter setter = HystrixThreadPoolProperties.Setter();
        HystrixProperty[] properties = hystrixCommand.threadPoolProperties();
        for(HystrixProperty property : properties) {
            Integer value = Integer.parseInt(property.value());
            String name = property.name();
            if("coreSize".equals(name)) {
                setter.withCoreSize(value);
            } else if("maxQueueSize".equals(name)) {
                setter.withMaxQueueSize(value);
            } else if("keepAliveTimeMinutes".equals(name)) {
                setter.withKeepAliveTimeMinutes(value);
            } else if("metricsRollingStatisticalWindowBuckets".equals(name)) {
                setter.withMetricsRollingStatisticalWindowBuckets(value);
            } else if("queueSizeRejectionThreshold".equals(name)) {
                setter.withQueueSizeRejectionThreshold(value);
            } else if("metricsRollingStatisticalWindowInMilliseconds".equals(name)) {
                setter.withMetricsRollingStatisticalWindowInMilliseconds(value);
            } else {
                throw new IllegalArgumentException("unsupported threadPoolPropery: " + name);
            }
        }

        return setter;
    }

}
