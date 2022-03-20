package com.alibaba.demo;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DegradeDemo {
	private static final String KEY = "/";
	private static final int threadCount = 100;
	private static AtomicInteger pass = new AtomicInteger();
	private static AtomicInteger block = new AtomicInteger();
	private static AtomicInteger total = new AtomicInteger();

	public static void main(String[] args) throws Exception {

		List<DegradeRule> rules = new ArrayList<DegradeRule>();
		DegradeRule rule = new DegradeRule();
		rule.setResource(KEY);
		// set threshold rt, 10 ms
		rule.setCount(10);
		rule.setGrade(RuleConstant.DEGRADE_GRADE_RT);
		rule.setTimeWindow(10);
		rules.add(rule);
		DegradeRuleManager.loadRules(rules);

		for (int i = 0; i < threadCount; i++) {
			Thread entryThread = new Thread(new Runnable() {

				@Override
				public void run() {
					while (true) {
						Entry entry = null;
						try {
							TimeUnit.MILLISECONDS.sleep(5);
							entry = SphU.entry(KEY);
							// token acquired
							pass.incrementAndGet();
							// sleep 600 ms, as rt
							TimeUnit.MILLISECONDS.sleep(600);
						} catch (Exception e) {
							block.incrementAndGet();
						} finally {
							total.incrementAndGet();
							if (entry != null) {
								entry.exit();
							}
						}
					}
				}
			});
			entryThread.setName("working-thread");
			entryThread.start();
		}
	}
}
