package com.alibaba.demo;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 系统保护规则是应用整体维度的，而不是资源维度的，并且仅对入口流量生效。
 * 入口流量指的是进入应用的流量（EntryType.IN）
 */
public class CustomRatelimiterDemo {

	private static AtomicInteger block = new AtomicInteger();

	public static void main(String[] args) {
		initRules();
		doEntry();
	}

	private static void initRules() {
		List<SystemRule> rules = new ArrayList<SystemRule>();
		SystemRule rule = new SystemRule();
		//限制最大负载
		rule.setHighestSystemLoad(3.0);
		// cpu负载60%
		rule.setHighestCpuUsage(0.6);
		// 设置平均响应时间 10 ms
		rule.setAvgRt(10);
		// 设置qps is 20
		rule.setQps(20);
		// 设置最大线程数 10
		rule.setMaxThread(10);

		rules.add(rule);
		SystemRuleManager.loadRules(Collections.singletonList(rule));
	}

	private static void doEntry() {
		Entry entry = null;
		try {
			entry = SphU.entry("methodA", EntryType.IN);
			//dosomething
		} catch (BlockException e1) {
			block.incrementAndGet();
			//dosomething
		} catch (Exception e2) {
			// biz exception
		} finally {
			if (entry != null) {
				entry.exit();
			}
		}
	}


}
