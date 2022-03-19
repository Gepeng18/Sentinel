package com.alibaba.demo.DynamicLoad;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.FileRefreshableDataSource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;

import java.util.List;

public class SimpleFileDataSourceDemo {

	private static final String KEY = "abc";

	public static void main(String[] args) throws Exception {
		SimpleFileDataSourceDemo simpleFileDataSourceDemo = new SimpleFileDataSourceDemo();
		simpleFileDataSourceDemo.init();
		Entry entry = null;
		try {
			entry = SphU.entry(KEY);
			// dosomething
		} catch (BlockException e1) {
			// dosomething
		} catch (Exception e2) {
			// biz exception
		} finally {
			if (entry != null) {
				entry.exit();
			}
		}
	}
	private void init() throws Exception {
		String flowRulePath = "/Users/luozhiyun/Downloads/test/FlowRule.json";
		// Data source for FlowRule
		FileRefreshableDataSource<List<FlowRule>> flowRuleDataSource = new FileRefreshableDataSource<>(
				flowRulePath, flowRuleListParser);
		FlowRuleManager.register2Property(flowRuleDataSource.getProperty());
	}
	private Converter<String, List<FlowRule>> flowRuleListParser = source -> JSON.parseObject(source,
			new TypeReference<List<FlowRule>>() {});
}