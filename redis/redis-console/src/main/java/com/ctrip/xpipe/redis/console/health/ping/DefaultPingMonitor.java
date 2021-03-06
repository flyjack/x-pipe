package com.ctrip.xpipe.redis.console.health.ping;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.ctrip.xpipe.redis.core.entity.ClusterMeta;
import com.ctrip.xpipe.redis.core.entity.DcMeta;
import com.ctrip.xpipe.redis.core.entity.RedisMeta;
import com.ctrip.xpipe.redis.core.entity.ShardMeta;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.ctrip.xpipe.metric.HostPort;
import com.ctrip.xpipe.redis.console.health.BaseSampleMonitor;
import com.ctrip.xpipe.redis.console.health.BaseSamplePlan;
import com.ctrip.xpipe.redis.console.health.PingCallback;
import com.ctrip.xpipe.redis.console.health.RedisSession;
import com.ctrip.xpipe.redis.console.health.Sample;
import org.unidal.tuple.Pair;

/**
 * @author marsqing
 *
 *         Dec 2, 2016 5:57:36 PM
 */
@Component
@Lazy
public class DefaultPingMonitor extends BaseSampleMonitor<InstancePingResult> implements PingMonitor {

	@Autowired
	private List<PingCollector> collectors;

	@Override
	protected void notifyCollectors(Sample<InstancePingResult> sample) {
		PingSampleResult sampleResult = converToSampleResult(sample);
		for (PingCollector collector : collectors) {
			collector.collect(sampleResult);
		}
	}

	private PingSampleResult converToSampleResult(Sample<InstancePingResult> sample) {

		BaseSamplePlan<InstancePingResult> plan = sample.getSamplePlan();
		PingSampleResult result = new PingSampleResult(plan.getClusterId(), plan.getShardId());

		for (Entry<HostPort, InstancePingResult> entry : sample.getSamplePlan().getHostPort2SampleResult().entrySet()) {
			HostPort hostPort = entry.getKey();
			result.addPong(hostPort, entry.getValue());
		}

		return result;
	}

	@Override
	public void startSample(BaseSamplePlan<InstancePingResult> plan) throws Exception {
		long startNanoTime = recordSample(plan);
		samplePing(startNanoTime, plan);
	}

	private void samplePing(final long startNanoTime, BaseSamplePlan<InstancePingResult> plan) {

		for (Entry<HostPort, InstancePingResult> entry : plan.getHostPort2SampleResult().entrySet()) {

			final HostPort hostPort = entry.getKey();
			log.debug("[ping]{}", hostPort);

			try{
				RedisSession session = findRedisSession(hostPort.getHost(), hostPort.getPort());
				session.ping(new PingCallback() {

					@Override
					public void pong(String pongMsg) {
						addInstanceSuccess(startNanoTime, hostPort.getHost(), hostPort.getPort(), null);
					}

					@Override
					public void fail(Throwable th) {
						addInstanceFail(startNanoTime, hostPort.getHost(), hostPort.getPort(), th);
					}
				});
			}catch (Exception e){
				log.error("[samplePing]" + hostPort, e);
			}
		}
	}

	@Override
	public Collection<BaseSamplePlan<InstancePingResult>> generatePlan(List<DcMeta> dcMetas) {

		Map<Pair<String, String>, BaseSamplePlan<InstancePingResult>> plans = new HashMap<>();

		for (DcMeta dcMeta : dcMetas) {
			for (ClusterMeta clusterMeta : dcMeta.getClusters().values()) {
				for (ShardMeta shardMeta : clusterMeta.getShards().values()) {
					Pair<String, String> cs = new Pair<>(clusterMeta.getId(), shardMeta.getId());
					PingSamplePlan plan = (PingSamplePlan) plans.get(cs);
					if (plan == null) {
						plan = new PingSamplePlan(clusterMeta.getId(), shardMeta.getId());
						plans.put(cs, plan);
					}

					for (RedisMeta redisMeta : shardMeta.getRedises()) {

						log.debug("[generatePlan]{}", redisMeta.desc());
						plan.addRedis(dcMeta.getId(), redisMeta, new InstancePingResult());
					}
				}
			}
		}

		return plans.values();
	}

}
