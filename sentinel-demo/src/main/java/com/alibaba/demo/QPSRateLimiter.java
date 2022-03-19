package com.alibaba.demo;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;

import java.util.ArrayList;
import java.util.List;

public class QPSRateLimiter {
	public static void main(String[] args) {
		List<FlowRule> rules = new ArrayList<FlowRule>();
		FlowRule rule1 = new FlowRule();
		rule1.setResource("abc");
		rule1.setCount(20);
		rule1.setGrade(RuleConstant.FLOW_GRADE_QPS);
		rule1.setLimitApp("default");
		rules.add(rule1);
		FlowRuleManager.loadRules(rules);

		Entry entry = null;

		try {
			entry = SphU.entry("abc");
			//dosomething
		} catch (BlockException e1) {

		} catch (Exception e2) {
			// biz exception
		} finally {
			if (entry != null) {
				entry.exit();
			}
		}
	}
}
