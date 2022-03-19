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
package com.alibaba.csp.sentinel.slots.block.flow;

import java.util.Collection;

import com.alibaba.csp.sentinel.cluster.ClusterStateManager;
import com.alibaba.csp.sentinel.cluster.server.EmbeddedClusterTokenServerProvider;
import com.alibaba.csp.sentinel.cluster.client.TokenClientProvider;
import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.TokenResult;
import com.alibaba.csp.sentinel.cluster.TokenService;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.alibaba.csp.sentinel.util.StringUtil;
import com.alibaba.csp.sentinel.util.function.Function;

/**
 * Rule checker for flow control rules.
 *
 * @author Eric Zhao
 */
public class FlowRuleChecker {

    public void checkFlow(Function<String, Collection<FlowRule>> ruleProvider, ResourceWrapper resource,
                          Context context, DefaultNode node, int count, boolean prioritized) throws BlockException {
        if (ruleProvider == null || resource == null) {
            return;
        }
        // 返回FlowRuleManager里面注册的所有规则
        Collection<FlowRule> rules = ruleProvider.apply(resource.getName());
        if (rules != null) {
            for (FlowRule rule : rules) {
                // 如果当前的请求不能通过，那么就抛出FlowException异常
                if (!canPassCheck(rule, context, node, count, prioritized)) {
                    // 返回false，就抛出异常了
                    throw new FlowException(rule.getLimitApp(), rule);
                }
            }
        }
    }

    public boolean canPassCheck(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node,
                                                    int acquireCount) {
        return canPassCheck(rule, context, node, acquireCount, false);
    }

    public boolean canPassCheck(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                                    boolean prioritized) {
        // 如果没有设置limitapp，那么不进行校验，默认会给个defualt
        String limitApp = rule.getLimitApp();
        if (limitApp == null) {
            return true;
        }

        // 集群模式
        if (rule.isClusterMode()) {
            return passClusterCheck(rule, context, node, acquireCount, prioritized);
        }
        // 本地模式
        return passLocalCheck(rule, context, node, acquireCount, prioritized);
    }

    private static boolean passLocalCheck(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                          boolean prioritized) {
        // 节点选择，根据不同的模式选择不同的节点
        Node selectedNode = selectNodeByRequesterAndStrategy(rule, context, node);
        if (selectedNode == null) {
            return true;
        }

        // 根据设置的规则来拦截
        // 这个rater就是generateRater方法中返回的
        return rule.getRater().canPass(selectedNode, acquireCount, prioritized);
    }

    static Node selectReferenceNode(FlowRule rule, Context context, DefaultNode node) {
        String refResource = rule.getRefResource();
        int strategy = rule.getStrategy();

        if (StringUtil.isEmpty(refResource)) {
            return null;
        }

        if (strategy == RuleConstant.STRATEGY_RELATE) {
            return ClusterBuilderSlot.getClusterNode(refResource);
        }

        if (strategy == RuleConstant.STRATEGY_CHAIN) {
            if (!refResource.equals(context.getName())) {
                return null;
            }
            return node;
        }
        // No node.
        return null;
    }

    private static boolean filterOrigin(String origin) {
        // Origin cannot be `default` or `other`.
        return !RuleConstant.LIMIT_APP_DEFAULT.equals(origin) && !RuleConstant.LIMIT_APP_OTHER.equals(origin);
    }

    /**
     * 这个方法主要是用来根据控制根据不同的规则，获取不同的node进行数据的统计。
     *
     * 在标记1中表示，如果流控规则配置了来源应用且不是"default"或者"other"这种特殊值，那么这种时候该规则就只对配置的来源应用生效。
     * 在标记2中表示，limitApp是"default"，代表针对所有应用进行统计。
     * 标记7中，这个是"other"值的处理，假设当前请求来源不在当前规则的limitApp中，则进行下面的处理。
     *
     * 官方文档解释如下：
     * default：表示不区分调用者，来自任何调用者的请求都将进行限流统计。如果这个资源名的调用总和超过了这条规则定义的阈值，则触发限流。
     * {some_origin_name}：表示针对特定的调用者，只有来自这个调用者的请求才会进行流量控制。例如 NodeA 配置了一条针对调用者caller1的规则，那么当且仅当来自 caller1 对 NodeA 的请求才会触发流量控制。
     * other：表示针对除 {some_origin_name} 以外的其余调用方的流量进行流量控制。例如，资源NodeA配置了一条针对调用者 caller1 的限流规则，同时又配置了一条调用者为 other 的规则，那么任意来自非 caller1 对 NodeA 的调用，都不能超过 other 这条规则定义的阈值
     * 同一个资源名可以配置多条规则，规则的生效顺序为：{some_origin_name} > other > default
     */
    static Node selectNodeByRequesterAndStrategy(/*@NonNull*/ FlowRule rule, Context context, DefaultNode node) {
        // The limit app should not be empty.
        String limitApp = rule.getLimitApp();
        int strategy = rule.getStrategy();
        String origin = context.getOrigin();

        if (limitApp.equals(origin) && filterOrigin(origin)) {
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                // Matches limit origin, return origin statistic node.
                return context.getOriginNode();
            }

            return selectReferenceNode(rule, context, node);
        } else if (RuleConstant.LIMIT_APP_DEFAULT.equals(limitApp)) {
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                // Return the cluster node.
                return node.getClusterNode();
            }

            return selectReferenceNode(rule, context, node);
        } else if (RuleConstant.LIMIT_APP_OTHER.equals(limitApp)
            && FlowRuleManager.isOtherOrigin(origin, rule.getResource())) {
            if (strategy == RuleConstant.STRATEGY_DIRECT) {
                return context.getOriginNode();
            }

            return selectReferenceNode(rule, context, node);
        }

        return null;
    }

    private static boolean passClusterCheck(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                            boolean prioritized) {
        try {
            TokenService clusterService = pickClusterService();
            if (clusterService == null) {
                return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
            }
            long flowId = rule.getClusterConfig().getFlowId();
            TokenResult result = clusterService.requestToken(flowId, acquireCount, prioritized);
            return applyTokenResult(result, rule, context, node, acquireCount, prioritized);
            // If client is absent, then fallback to local mode.
        } catch (Throwable ex) {
            RecordLog.warn("[FlowRuleChecker] Request cluster token unexpected failed", ex);
        }
        // Fallback to local flow control when token client or server for this rule is not available.
        // If fallback is not enabled, then directly pass.
        return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
    }

    private static boolean fallbackToLocalOrPass(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                                 boolean prioritized) {
        if (rule.getClusterConfig().isFallbackToLocalWhenFail()) {
            return passLocalCheck(rule, context, node, acquireCount, prioritized);
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

    private static boolean applyTokenResult(/*@NonNull*/ TokenResult result, FlowRule rule, Context context,
                                                         DefaultNode node,
                                                         int acquireCount, boolean prioritized) {
        switch (result.getStatus()) {
            case TokenResultStatus.OK:
                return true;
            case TokenResultStatus.SHOULD_WAIT:
                // Wait for next tick.
                try {
                    Thread.sleep(result.getWaitInMs());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            case TokenResultStatus.NO_RULE_EXISTS:
            case TokenResultStatus.BAD_REQUEST:
            case TokenResultStatus.FAIL:
            case TokenResultStatus.TOO_MANY_REQUEST:
                return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
            case TokenResultStatus.BLOCKED:
            default:
                return false;
        }
    }
}