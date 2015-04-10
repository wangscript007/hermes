package com.ctrip.hermes.broker.queue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ctrip.hermes.broker.transport.transmitter.TpgRelayer;
import com.ctrip.hermes.core.bo.Tpg;
import com.ctrip.hermes.core.message.ConsumerMessageBatch;
import com.ctrip.hermes.core.utils.PlexusComponentLocator;

/**
 * @author Leo Liang(jhliang@ctrip.com)
 *
 */
public class DefaultMessageQueuePuller implements MessageQueuePuller {

	private Tpg m_tpg;

	private ShutdownListener m_listener;

	private Thread m_workerThread;

	private AtomicBoolean m_started = new AtomicBoolean(false);

	private TpgRelayer m_relayer;

	private MessageQueueCursor m_queueCursor;

	public DefaultMessageQueuePuller(Tpg tpg, TpgRelayer relayer, ShutdownListener listener) {
		m_tpg = tpg;
		m_relayer = relayer;
		m_listener = listener;

		m_workerThread = new Thread(new PullerTask());
		m_workerThread.setDaemon(true);
		m_workerThread.setName(String.format("PullerThread-%s-%d-%s", m_tpg.getTopic(), m_tpg.getPartition(),
		      m_tpg.getGroupId()));
	}

	public void start() {
		if (m_started.compareAndSet(false, true)) {
			m_queueCursor = PlexusComponentLocator.lookup(MessageQueueManager.class).createCursor(m_tpg);
			m_workerThread.start();
		}
	}

	protected ConsumerMessageBatch pullMessages(int batchSize) {
		return m_queueCursor.next(batchSize);
	}

	private class PullerTask implements Runnable {

		@Override
		public void run() {
			ConsumerMessageBatch batch = null;

			while (!Thread.currentThread().isInterrupted()) {
				try {
					if (m_relayer == null) {
						TimeUnit.SECONDS.sleep(1);
						continue;
					}

					if (m_relayer.isClosed()) {
						// TODO log
						return;
					}

					int availableSize = m_relayer.availableSize();
					if (availableSize > 0) {
						if (batch == null) {
							batch = pullMessages(availableSize);
						}

						if (m_relayer.relay(batch)) {
							batch = null;
						}
					} else {
						TimeUnit.MILLISECONDS.sleep(50);
					}

				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} catch (Exception e) {
					// TODO
					e.printStackTrace();
				} finally {
					m_listener.onShutdown();
				}
			}

		}
	}

}
