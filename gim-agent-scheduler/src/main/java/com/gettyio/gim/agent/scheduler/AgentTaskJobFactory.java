/*
 * Copyright 2026 gogym
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gettyio.gim.agent.scheduler;

import org.quartz.Job;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.spi.JobFactory;
import org.quartz.spi.TriggerFiredBundle;

/**
 * Quartz 作业工厂：为 {@link AgentTaskJob} 注入共享执行器（替代原 Spring 依赖注入），
 * 其它作业类按无参构造兜底实例化。
 * <p>装配方在 {@code Scheduler.start()} 前通过
 * {@code scheduler.setJobFactory(new AgentTaskJobFactory(executor))} 安装。</p>
 *
 * @author gogym
 */
public class AgentTaskJobFactory implements JobFactory {

    private final AgentTaskExecutor executor;

    public AgentTaskJobFactory(AgentTaskExecutor executor) {
        this.executor = executor;
    }

    /** 反射实例化作业类；{@link AgentTaskJob} 额外注入共享执行器，其它作业按无参构造兜底。 */
    @Override
    public Job newJob(TriggerFiredBundle bundle, Scheduler scheduler) throws SchedulerException {
        Class<? extends Job> jobClass = bundle.getJobDetail().getJobClass();
        Job job;
        try {
            job = jobClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SchedulerException("作业实例化失败: " + jobClass.getName(), e);
        }
        if (job instanceof AgentTaskJob agentTaskJob) {
            agentTaskJob.setExecutor(executor);
        }
        return job;
    }
}
