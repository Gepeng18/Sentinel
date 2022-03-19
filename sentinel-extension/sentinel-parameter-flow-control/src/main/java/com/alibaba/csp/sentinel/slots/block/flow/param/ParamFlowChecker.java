/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.flow.param;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.csp.sentinel.cluster.ClusterStateManager;
import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.TokenService;
import com.alibaba.csp.sentinel.cluster.client.TokenClientProvider;
import com.alibaba.csp.sentinel.cluster.server.EmbeddedClusterTokenServerProvider;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.statistic.cache.CacheMap;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * Rule checker for parameter flow control.
 *
 * @author Eric Zhao
 * @since 0.2.0
 */
public final class ParamFlowChecker {

    public static boolean passCheck(ResourceWrapper resourceWrapper, /*@Valid*/ ParamFlowRule rule, /*@Valid*/ int count,
                             Object... args) {
        if (args == null) {
            return true;
        }

        int paramIdx = rule.getParamIdx();
        if (args.length <= paramIdx) {
            return true;
        }

        // 获取需要被限制的那个参数值，比如传入了两个参数，uid 和 did，我们只针对第二个值进行限流，所以这里取出传入的did的值
        // Get parameter value.
        Object value = args[paramIdx];

        // Assign value with the result of paramFlowKey method
        if (value instanceof ParamFlowArgument) {
            value = ((ParamFlowArgument) value).paramFlowKey();
        }
        // If value is null, then pass
        if (value == null) {
            return true;
        }

        if (rule.isClusterMode() && rule.getGrade() == RuleConstant.FLOW_GRADE_QPS) {
            return passClusterCheck(resourceWrapper, rule, count, value);
        }

        return passLocalCheck(resourceWrapper, rule, count, value);
    }

    private static boolean passLocalCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int count,
                                          Object value) {
        try {
            // 如果参数是集合
            if (Collection.class.isAssignableFrom(value.getClass())) {
                for (Object param : ((Collection)value)) {
                    if (!passSingleValueCheck(resourceWrapper, rule, count, param)) {
                        return false;
                    }
                }
            } else if (value.getClass().isArray()) {
                // 如果参数是数组
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    Object param = Array.get(value, i);
                    if (!passSingleValueCheck(resourceWrapper, rule, count, param)) {
                        return false;
                    }
                }
            } else {
                return passSingleValueCheck(resourceWrapper, rule, count, value);
            }
        } catch (Throwable e) {
            RecordLog.warn("[ParamFlowChecker] Unexpected error", e);
        }

        return true;
    }

    static boolean passSingleValueCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int acquireCount,
                                        Object value) {
        // QPS限制
        if (rule.getGrade() == RuleConstant.FLOW_GRADE_QPS) {
            if (rule.getControlBehavior() == RuleConstant.CONTROL_BEHAVIOR_RATE_LIMITER) {
                return passThrottleLocalCheck(resourceWrapper, rule, acquireCount, value);
            } else {
                return passDefaultLocalCheck(resourceWrapper, rule, acquireCount, value);
            }
        } else if (rule.getGrade() == RuleConstant.FLOW_GRADE_THREAD) {
            // 线程数限制
            Set<Object> exclusionItems = rule.getParsedHotItems().keySet();
            long threadCount = getParameterMetric(resourceWrapper).getThreadCount(rule.getParamIdx(), value);
            if (exclusionItems.contains(value)) {
                int itemThreshold = rule.getParsedHotItems().get(value);
                return ++threadCount <= itemThreshold;
            }
            long threshold = (long)rule.getCount();
            return ++threadCount <= threshold;
        }

        return true;
    }

    static boolean passDefaultLocalCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int acquireCount,
                                         Object value) {
        // 获取资源的ParameterMetric
        ParameterMetric metric = getParameterMetric(resourceWrapper);
        // 获取该资源下，该规则下的tokenCounters和timeCounters
        CacheMap<Object, AtomicLong> tokenCounters = metric == null ? null : metric.getRuleTokenCounter(rule);
        CacheMap<Object, AtomicLong> timeCounters = metric == null ? null : metric.getRuleTimeCounter(rule);

        if (tokenCounters == null || timeCounters == null) {
            return true;
        }

        // Calculate max token count (threshold)
        Set<Object> exclusionItems = rule.getParsedHotItems().keySet();
        long tokenCount = (long)rule.getCount();
        if (exclusionItems.contains(value)) {
            tokenCount = rule.getParsedHotItems().get(value);
        }

        if (tokenCount == 0) {
            return false;
        }

        long maxCount = tokenCount + rule.getBurstCount();
        if (acquireCount > maxCount) {
            return false;
        }

        // token够用
        while (true) {
            long currentTime = TimeUtil.currentTimeMillis();

            // 对于参数的取值，先置一个设置token的时间
            AtomicLong lastAddTokenTime = timeCounters.putIfAbsent(value, new AtomicLong(currentTime));
            if (lastAddTokenTime == null) {
                // 从来没获取过token，那么这次直接减token就行
                // Token never added, just replenish the tokens and consume {@code acquireCount} immediately.
                tokenCounters.putIfAbsent(value, new AtomicLong(maxCount - acquireCount));
                return true;
            }

            // 距离上一次获取token花了多久
            // Calculate the time duration since last token was added.
            long passTime = currentTime - lastAddTokenTime.get();
            // A simplified token bucket algorithm that will replenish the tokens only when statistic window has passed.
            // 距离上次获取token的时间超过了窗口时间，则放行，直接减token就行
            if (passTime > rule.getDurationInSec() * 1000) {
                // 老的token值如果不存在
                AtomicLong oldQps = tokenCounters.putIfAbsent(value, new AtomicLong(maxCount - acquireCount));
                if (oldQps == null) {
                    // Might not be accurate here.
                    lastAddTokenTime.set(currentTime);
                    return true;
                } else {
                    // 老的token值
                    long restQps = oldQps.get();
                    // 过了passTime时间，应该涨上去的 count
                    long toAddCount = (passTime * tokenCount) / (rule.getDurationInSec() * 1000);
                    // 又减去本次耗费的count
                    long newQps = toAddCount + restQps > maxCount ? (maxCount - acquireCount)
                        : (restQps + toAddCount - acquireCount);

                    // 本次耗费后，如果不够，就false
                    if (newQps < 0) {
                        return false;
                    }
                    // 本次耗费后，如果够，就耗费掉(cas设置了，就表明耗费掉了)
                    if (oldQps.compareAndSet(restQps, newQps)) {
                        lastAddTokenTime.set(currentTime);
                        return true;
                    }
                    Thread.yield();
                }
            } else {
                // 获取老token
                AtomicLong oldQps = tokenCounters.get(value);
                if (oldQps != null) {
                    long oldQpsValue = oldQps.get();
                    // 本次耗费后，如果大于0，则设置，否则直接返回false
                    if (oldQpsValue - acquireCount >= 0) {
                        if (oldQps.compareAndSet(oldQpsValue, oldQpsValue - acquireCount)) {
                            return true;
                        }
                    } else {
                        return false;
                    }
                }
                Thread.yield();
            }
        }
    }

    static boolean passThrottleLocalCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int acquireCount,
                                          Object value) {
        // 获取资源的ParameterMetric
        ParameterMetric metric = getParameterMetric(resourceWrapper);
        // 获取该资源下，该规则下的: (value, timeRecorder)
        CacheMap<Object, AtomicLong> timeRecorderMap = metric == null ? null : metric.getRuleTimeCounter(rule);
        if (timeRecorderMap == null) {
            return true;
        }

        // Calculate max token count (threshold)
        Set<Object> exclusionItems = rule.getParsedHotItems().keySet();
        long tokenCount = (long)rule.getCount();
        if (exclusionItems.contains(value)) {
            tokenCount = rule.getParsedHotItems().get(value);
        }

        if (tokenCount == 0) {
            return false;
        }

        long costTime = Math.round(1.0 * 1000 * acquireCount * rule.getDurationInSec() / tokenCount);
        while (true) {
            long currentTime = TimeUtil.currentTimeMillis();
            // 上一次访问的时间
            AtomicLong timeRecorder = timeRecorderMap.putIfAbsent(value, new AtomicLong(currentTime));
            if (timeRecorder == null) {
                return true;
            }
            //AtomicLong timeRecorder = timeRecorderMap.get(value);
            long lastPassTime = timeRecorder.get();
            // 需要本次耗费后，希望的时间
            long expectedTime = lastPassTime + costTime;

            // 在可接受范围内(即小于排队时间)
            if (expectedTime <= currentTime || expectedTime - currentTime < rule.getMaxQueueingTimeMs()) {
                // 判断上一次访问时间是不是还是刚刚读的那个时间(有没有被cas过)
                AtomicLong lastPastTimeRef = timeRecorderMap.get(value);
                if (lastPastTimeRef.compareAndSet(lastPassTime, currentTime)) {
                    // 没有被cas过，就把期望的时间设置为上一次访问时间，然后睡对应的等待时间
                    long waitTime = expectedTime - currentTime;
                    if (waitTime > 0) {
                        lastPastTimeRef.set(expectedTime);
                        try {
                            TimeUnit.MILLISECONDS.sleep(waitTime);
                        } catch (InterruptedException e) {
                            RecordLog.warn("passThrottleLocalCheck: wait interrupted", e);
                        }
                    }
                    return true;
                } else {
                    Thread.yield();
                }
            } else {
                return false;
            }
        }
    }

    private static ParameterMetric getParameterMetric(ResourceWrapper resourceWrapper) {
        // Should not be null.
        return ParameterMetricStorage.getParamMetric(resourceWrapper);
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> toCollection(Object value) {
        if (value instanceof Collection) {
            return (Collection<Object>)value;
        } else if (value.getClass().isArray()) {
            List<Object> params = new ArrayList<Object>();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object param = Array.get(value, i);
                params.add(param);
            }
            return params;
        } else {
            return Collections.singletonList(value);
        }
    }

    private static boolean passClusterCheck(ResourceWrapper resourceWrapper, ParamFlowRule rule, int count,
                                            Object value) {
        try {
            Collection<Object> params = toCollection(value);

            TokenService clusterService = pickClusterService();
            if (clusterService == null) {
                // No available cluster client or server, fallback to local or
                // pass in need.
                return fallbackToLocalOrPass(resourceWrapper, rule, count, params);
            }

            TokenResult result = clusterService.requestParamToken(rule.getClusterConfig().getFlowId(), count, params);
            switch (result.getStatus()) {
                case TokenResultStatus.OK:
                    return true;
                case TokenResultStatus.BLOCKED:
                    return false;
                default:
                    return fallbackToLocalOrPass(resourceWrapper, rule, count, params);
            }
        } catch (Throwable ex) {
            RecordLog.warn("[ParamFlowChecker] Request cluster token for parameter unexpected failed", ex);
            return fallbackToLocalOrPass(resourceWrapper, rule, count, value);
        }
    }

    private static boolean fallbackToLocalOrPass(ResourceWrapper resourceWrapper, ParamFlowRule rule, int count,
                                                 Object value) {
        if (rule.getClusterConfig().isFallbackToLocalWhenFail()) {
            return passLocalCheck(resourceWrapper, rule, count, value);
        } else {
            // The rule won't be activated, just pass.
            return true;
        }
    }

    private static TokenService pickClusterService() {
        if (ClusterStateManager.isClient()) {
            return TokenClientProvider.getClient();
        }
        if (ClusterStateManager.isServer()) {
            return EmbeddedClusterTokenServerProvider.getServer();
        }
        return null;
    }

    private ParamFlowChecker() {
    }
}
