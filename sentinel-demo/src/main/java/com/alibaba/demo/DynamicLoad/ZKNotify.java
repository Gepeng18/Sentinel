package com.alibaba.demo.DynamicLoad;

import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.datasource.zookeeper.ZookeeperDataSource;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;

import java.util.List;

public class ZKNotify {
	public static void main(String[] args) {
		final String remoteAddress = "127.0.0.1:2181";
		final String path = "/Sentinel-Demo/SYSTEM-CODE-DEMO-FLOW";
		ReadableDataSource<String, List<FlowRule>> flowRuleDataSource = new ZookeeperDataSource<>(remoteAddress, path,
				source -> JSON.parseObject(source, new TypeReference<List<FlowRule>>() {}));
		FlowRuleManager.register2Property(flowRuleDataSource.getProperty());
	}
}
