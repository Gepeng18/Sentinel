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
package com.alibaba.csp.sentinel.node.metric;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.config.SentinelConfig;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;

/**
 * @author jialiang.linjl
 */
public class MetricTimerListener implements Runnable {

    // 两个配置参数
    private static final MetricWriter metricWriter = new MetricWriter(SentinelConfig.singleMetricFileSize(),
        SentinelConfig.totalMetricFileCount());

    /**
     * 这个run方法里面主要是做定时的数据采集，然后写到log文件里去
     */
    @Override
    public void run() {
        Map<Long, List<MetricNode>> maps = new TreeMap<>();
        for (Entry<ResourceWrapper, ClusterNode> e : ClusterBuilderSlot.getClusterNodeMap().entrySet()) {
            ClusterNode node = e.getValue();
            Map<Long, MetricNode> metrics = node.metrics();
            // 将metrics和node封装到map中
            aggregate(maps, metrics, node);
        }
        // 将全局入口的metrics和node也封装到map中
        aggregate(maps, Constants.ENTRY_NODE.metrics(), Constants.ENTRY_NODE);
        if (!maps.isEmpty()) {
            for (Entry<Long, List<MetricNode>> entry : maps.entrySet()) {
                try {
                    // 保存到日志中
                    metricWriter.write(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    RecordLog.warn("[MetricTimerListener] Write metric error", e);
                }
            }
        }
    }

    /**
     * 将metrics 和 node 封装到 map 中
     */
    private void aggregate(Map<Long, List<MetricNode>> maps, Map<Long, MetricNode> metrics, ClusterNode node) {
        for (Entry<Long, MetricNode> entry : metrics.entrySet()) {
            long time = entry.getKey();
            // 将node的name 和 resourceType 放到entry的value中
            MetricNode metricNode = entry.getValue();
            metricNode.setResource(node.getName());
            metricNode.setClassification(node.getResourceType());
            // 将entry的value放到map的value(List)中
            maps.computeIfAbsent(time, k -> new ArrayList<MetricNode>());
            List<MetricNode> nodes = maps.get(time);
            nodes.add(entry.getValue());
        }
    }

}
