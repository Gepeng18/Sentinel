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
package com.alibaba.csp.sentinel.annotation.aspectj;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;

import java.lang.reflect.Method;

/**
 * Aspect for methods with {@link SentinelResource} annotation.
 *
 * @author Eric Zhao
 */
@Aspect  // aspect切面
public class SentinelResourceAspect extends AbstractSentinelAspectSupport {

    // 指定切入点为环绕通知 around advice
    @Pointcut("@annotation(com.alibaba.csp.sentinel.annotation.SentinelResource)")
    public void sentinelResourceAnnotationPointcut() {
    }

    // 环绕通知
    @Around("sentinelResourceAnnotationPointcut()")
    public Object invokeResourceWithSentinel(ProceedingJoinPoint pjp) throws Throwable {
        // 获取本注解增强的那个方法
        Method originMethod = resolveMethod(pjp);

        SentinelResource annotation = originMethod.getAnnotation(SentinelResource.class);
        if (annotation == null) {
            // Should not go through here.
            throw new IllegalStateException("Wrong state for SentinelResource annotation");
        }
        // resourceName即为注解中的value值，如果注解value为空，则为自定义的类名+方法名+参数名
        String resourceName = getResourceName(annotation.value(), originMethod);
        EntryType entryType = annotation.entryType();  // 注解的entry类型，in/out，默认是out
        int resourceType = annotation.resourceType();  // 注解的资源类型
        Entry entry = null;
        try {
            // 要织入，增强的功能
            // 将注解的value(resourceName)，resourceType，entryType 以及 参数传进去
            entry = SphU.entry(resourceName, resourceType, entryType, pjp.getArgs());
            // 调用目标
            return pjp.proceed();
        } catch (BlockException ex) {
            // 处理阻塞异常,这就是鼎鼎大名的限流策略,sentinel的目的是抛出BlockException，
            // 然后由下面的代码找到SentinelResource中对应的阻塞处理器，然后执行相应操作
            return handleBlockException(pjp, annotation, ex);
        } catch (Throwable ex) {
            // 处理代码中抛出的错误，这里就是鼎鼎大名的降级策略,sentinel的目的是抛出Throwable，
            // 然后由下面的代码找到SentinelResource中对应的fallBack处理器，然后执行相应操作
            Class<? extends Throwable>[] exceptionsToIgnore = annotation.exceptionsToIgnore();
            // The ignore list will be checked first.
            // 注解中指定了一些可以忽视的异常，检测后，抛出异常
            // 所以我理解，这里注解的忽视异常意思是让sentinel忽视，别处理，直接抛出来
            if (exceptionsToIgnore.length > 0 && exceptionBelongsTo(ex, exceptionsToIgnore)) {
                throw ex;
            }
            // 注解中指定了一些需要追踪的异常，检测到后，进行处理
            // 这里分为配置了fallBack以及defaultCallBack
            if (exceptionBelongsTo(ex, annotation.exceptionsToTrace())) {
                traceException(ex);
                return handleFallback(pjp, annotation, ex);
            }

            // 没有fallBack函数可以处理异常，所以将其抛出。
            throw ex;
        } finally {
            if (entry != null) {
                entry.exit(1, pjp.getArgs());
            }
        }
    }
}
