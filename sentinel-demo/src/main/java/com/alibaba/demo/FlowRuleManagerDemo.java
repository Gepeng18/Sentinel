package com.alibaba.demo;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sentinel的QPS流量控制
 */
public class FlowRuleManagerDemo {
	private static AtomicInteger pass = new AtomicInteger();
	private static AtomicInteger block = new AtomicInteger();
	private static AtomicInteger total = new AtomicInteger();
	private static AtomicInteger activeThread = new AtomicInteger();

	private static volatile boolean stop = false;

	private static int seconds = 60 + 40;
	private static volatile int methodBRunningTime = 2000;

	public static void main(String[] args) throws Exception {
		System.out.println(
				"MethodA will call methodB. After running for a while, methodB becomes fast, "
						+ "which make methodA also become fast ");
		tick();
		initFlowRule();

		Entry methodA = null;
		try {
			TimeUnit.MILLISECONDS.sleep(5);
			methodA = SphU.entry("methodA");
			activeThread.incrementAndGet();
			//Entry methodB = SphU.entry("methodB");
			TimeUnit.MILLISECONDS.sleep(methodBRunningTime);
			//methodB.exit();
			pass.addAndGet(1);
		} catch (BlockException e1) {
			block.incrementAndGet();
		} catch (Exception e2) {
			// biz exception
		} finally {
			total.incrementAndGet();
			if (methodA != null) {
				methodA.exit();
				activeThread.decrementAndGet();
			}
		}
	}

	/**
	 * 配置规则
	 */
	private static void initFlowRule() {
		List<FlowRule> rules = new ArrayList<FlowRule>();
		FlowRule rule1 = new FlowRule();
		rule1.setResource("methodA");
		// set limit concurrent thread for 'methodA' to 20
		rule1.setCount(20);
		rule1.setGrade(RuleConstant.FLOW_GRADE_THREAD);
		rule1.setLimitApp("default");

		rules.add(rule1);
		FlowRuleManager.loadRules(rules);
	}

	private static void tick() {
		Thread timer = new Thread(new TimerTask());
		timer.setName("sentinel-timer-task");
		timer.start();
	}

	static class TimerTask implements Runnable {

		@Override
		public void run() {
			long start = System.currentTimeMillis();
			System.out.println("begin to statistic!!!");

			long oldTotal = 0;
			long oldPass = 0;
			long oldBlock = 0;

			while (!stop) {
				try {
					TimeUnit.SECONDS.sleep(1);
				} catch (InterruptedException e) {
				}
				long globalTotal = total.get();
				long oneSecondTotal = globalTotal - oldTotal;
				oldTotal = globalTotal;

				long globalPass = pass.get();
				long oneSecondPass = globalPass - oldPass;
				oldPass = globalPass;

				long globalBlock = block.get();
				long oneSecondBlock = globalBlock - oldBlock;
				oldBlock = globalBlock;

				System.out.println(seconds + " total qps is: " + oneSecondTotal);
				System.out.println(TimeUtil.currentTimeMillis() + ", total:" + oneSecondTotal
						+ ", pass:" + oneSecondPass
						+ ", block:" + oneSecondBlock
						+ " activeThread:" + activeThread.get());
				if (seconds-- <= 0) {
					stop = true;
				}
				if (seconds == 40) {
					System.out.println("method B is running much faster; more requests are allowed to pass");
					methodBRunningTime = 20;
				}
			}

			long cost = System.currentTimeMillis() - start;
			System.out.println("time cost: " + cost + " ms");
			System.out.println("total:" + total.get() + ", pass:" + pass.get()
					+ ", block:" + block.get());
			System.exit(0);
		}
	}
}