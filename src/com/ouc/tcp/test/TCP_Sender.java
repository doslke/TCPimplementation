/***************************2.1: ACK/NACK
**************************** Feng Hong; 2015-12-09*/

package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Sender extends TCP_Sender_ADT {
	
	private TCP_PACKET tcpPack;	//待发送的TCP数据报
	
	// Configuration: Set to true for Reno, false for Tahoe
    public static final boolean IS_RENO = true; 
	
	// RDT 4.0: GBN Variables
	private double cwnd = 1.0;  // Congestion Window (in packets)
	private int ssthresh = 16;  // Slow Start Threshold
	private int dupACKcount = 0;
	private int lastAckRecv = -1; // To track duplicate ACKs
    private boolean isFastRecovery = false; // Reno State
    private boolean lossInjected = false;

	private int base = 1;       // 基序号
	private int nextSeqNum = 1; // 下一个序号
	// GBN需要缓存已发送但未确认的包，这里简单使用 List 或 Map
	// 由于序号是 index * length + 1，不是连续整数，用 Map 更方便
	private java.util.concurrent.ConcurrentHashMap<Integer, TCP_PACKET> sentPackets = new java.util.concurrent.ConcurrentHashMap<Integer, TCP_PACKET>();
	private UDT_Timer timer;
	private UDT_RetransTask retransTask;
	
	/*构造函数*/
	public TCP_Sender() {
		super();	//调用超类构造函数
		super.initTCP_Sender(this);		//初始化TCP发送端
		timer = new UDT_Timer();
		
		// Initialize CWND Log
		try {
			java.io.File logFile = new java.io.File("cwnd_data.txt");
			java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(logFile, false)); // Overwrite
			writer.write("Timestamp,CWND,SSTHRESH,Event\n");
			writer.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void logCwnd(String event) {
		try {
			java.io.FileWriter fw = new java.io.FileWriter("cwnd_data.txt", true);
			long time = System.currentTimeMillis();
			fw.write(time + "," + String.format("%.2f", cwnd) + "," + ssthresh + "," + event + "\n");
			fw.close();
		} catch (Exception e) {}
	}
	
	@Override
	//可靠发送（应用层调用）：封装应用层数据，产生TCP数据报；
	public void rdt_send(int dataIndex, int[] appData) {
		
		// 4.0 GBN: 检查窗口是否已满
		// 这里简化处理：如果是GBN，通常 send 是非阻塞的，直到窗口满。
		// 但这个框架似乎是 "stop-and-wait" 风格调用 rdt_send?
		// 实际上测试代码可能是一个循环调用 rdt_send。
		// 我们需要阻塞如果窗口满了。
		
		int currentSeq = dataIndex * appData.length + 1;
		int dataLen = appData.length;
		
		// 窗口计算：(nextSeqNum < base + windowSize * dataLen)
		// 注意 sequence number 不是 +1 递增，而是 +100 递增
		// 所以窗口判断应该是：nextSeqNum < base + windowSize * 100
		
		// Tahoe Flow Control: Limit by cwnd
		// 必须等待窗口有空位
		// 在真实GBN中，上层调用应该被拒绝或缓存。这里我们简单用 while 循环阻塞
		while (nextSeqNum >= base + (int)cwnd * 100) {
			try { Thread.sleep(10); } catch (InterruptedException e) {}
		}
		
		//生成TCP数据报（设置序号和数据字段/校验和),注意打包的顺序
		// Use clone() to copy all fields including flags
		TCP_HEADER newTcpH = null;
		try {
			newTcpH = (TCP_HEADER) tcpH.clone();
		} catch (CloneNotSupportedException e) {
			e.printStackTrace();
		}
		
		newTcpH.setTh_seq(currentSeq);
		newTcpH.setTh_doff(tcpH.getTh_doff()); 
		// sum is calculated later

		TCP_SEGMENT newTcpS = new TCP_SEGMENT();
		newTcpS.setData(appData);

		tcpPack = new TCP_PACKET(newTcpH, newTcpS, destinAddr);		
				
		newTcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
		tcpPack.setTcpH(newTcpH);
		
		// 缓存包
		sentPackets.put(currentSeq, tcpPack);
		
		//发送TCP数据报
		udt_send(tcpPack);
		
		// 更新 nextSeqNum
		// 注意：这里的 nextSeqNum 应该等于 currentSeq + dataLen
		// 但 rdt_send 的 dataIndex 参数决定了 seq。
		// 我们假设调用是顺序的。
		nextSeqNum = currentSeq + dataLen;
		
		// 如果是基序号包，启动定时器
		if (base == currentSeq) {
			if (retransTask != null) retransTask.cancel();
			retransTask = new UDT_RetransTask(this);
			timer.schedule(retransTask, 600, 600); // Reduce timeout to 600ms for faster test
		}
		
		// 不再阻塞等待 ACK，立即返回允许发送下一个
	}
	
	@Override
	//不可靠发送：将打包好的TCP数据报通过不可靠传输信道发送；仅需修改错误标志
    public void udt_send(TCP_PACKET stcpPack) {
        int seq = stcpPack.getTcpH().getTh_seq();
        
        if (!lossInjected && seq == 701) {
             lossInjected = true;
             // Simulate loss by NOT sending the packet
             return;
        }
        
        client.send(stcpPack);
	}
	
	@Override
	// waitACK: 循环检查ACK队列。
	// 4.0 GBN: 累积确认
	public void waitACK() {
		//循环检查ackQueue
		//循环检查确认号对列中是否有新收到的ACK		
		if(!ackQueue.isEmpty()){
			int currentAck=ackQueue.poll();
			System.out.println("Ack Received: "+currentAck);
			
			// GBN: 累积确认
			// 如果收到 ACK n，意味着 n 及之前的所有包都已确认
			// 更新 base = currentAck + 1 (或者根据协议定义，ack是下一个期待的seq)
			// 这里假设 ack 是已接收的最后一个 seq。
			// 如果协议是：ack = expected seq，那么 base = currentAck
			// 查看 Receiver: tcpH.setTh_ack(recvSeq); -> 确认收到的 seq
			// 所以 Receiver 发送的是 "我收到了 X"。
			// 那么 base 应该更新为 X + dataLen (下一个)
			// 也就是 base 推进到 > currentAck
			
			if (currentAck >= base) {
				// New ACK (Cumulative)
				
				if (IS_RENO && isFastRecovery) {
					// Reno: Exit Fast Recovery
					cwnd = ssthresh;
					isFastRecovery = false;
					dupACKcount = 0;
					System.out.println("[Reno] Fast Recovery -> Congestion Avoidance (New ACK). cwnd set to ssthresh: " + cwnd);
					logCwnd("RenoExitFastRecovery");
				} else {
					// Tahoe/Reno Normal Mode
					if (cwnd < ssthresh) {
						// Slow Start
						cwnd += 1.0;
						System.out.println("[Slow Start] cwnd increased to " + cwnd);
						logCwnd("SlowStart");
					} else {
						// Congestion Avoidance
						cwnd += 1.0 / cwnd;
						System.out.println("[Congestion Avoidance] cwnd increased to " + cwnd);
						logCwnd("CongestionAvoidance");
					}
					dupACKcount = 0;
					// Tahoe resets here implicitly by doing nothing special (already out of fast retransmit if it was there)
				}
				
				lastAckRecv = currentAck;
				
				// 移动窗口
				// Update base based on the packet length
				TCP_PACKET ackedPacket = sentPackets.get(currentAck);
				int packetLen = 100; // Default fallback
				if (ackedPacket != null) {
					packetLen = ackedPacket.getTcpS().getData().length;
				}
				
				// 移除已确认的包
				java.util.Iterator<Integer> it = sentPackets.keySet().iterator();
				while(it.hasNext()){
					int seq = it.next();
					if(seq <= currentAck){
						it.remove();
					}
				}
				
				base = currentAck + packetLen; 
				System.out.println("Window moved. Base: " + base + " NextSeq: " + nextSeqNum);

				// 重置定时器
				if (base == nextSeqNum) {
					// 窗口空了，停止计时器
					if (retransTask != null) {
						retransTask.cancel();
						retransTask = null;
					}
				} else {
					// 还有未确认包，重启计时器
					if (retransTask != null) retransTask.cancel();
					retransTask = new UDT_RetransTask(this);
					timer.schedule(retransTask, 600, 600);
				}
			} else {
				// Duplicate ACK Handling
				// Check if it is a duplicate of the last valid ACK
                if (currentAck == lastAckRecv) {
                    dupACKcount++;
                    System.out.println("Duplicate ACK found. Count: " + dupACKcount);
                    
                    if (dupACKcount == 3) {
                        if (IS_RENO) {
                            // Reno: Fast Retransmit & Enter Fast Recovery
                            System.out.println("[Reno] 3 Duplicate ACKs! -> Fast Retransmit");
                            ssthresh = Math.max(2, (int)cwnd / 2);
                            cwnd = ssthresh + 3;
                            isFastRecovery = true;
                            System.out.println("[Reno] Entering Fast Recovery. ssthresh -> " + ssthresh + ", cwnd -> " + cwnd);
                            logCwnd("RenoFastRetransmit");
                            TCP_PACKET missing = sentPackets.get(base);
                            if (missing != null) {
                                System.out.println("[Reno] Retransmitting packet SEQ: " + base);
                                udt_send(missing);
                            } else {
                                System.out.println("[Reno] ERROR: Missing packet not found in buffer! Base: " + base);
                            }
                        } else {
                            // Tahoe: Fast Retransmit
                            System.out.println("[Tahoe] 3 Duplicate ACKs! -> Fast Retransmit");
                            ssthresh = Math.max(2, (int)cwnd / 2);
                            cwnd = 1.0;
                            dupACKcount = 0; // Reset dupACKs in Tahoe
                            System.out.println("[Tahoe] Entering Slow Start. ssthresh -> " + ssthresh + ", cwnd -> " + cwnd);
                            logCwnd("TahoeFastRetransmit");
                        }
                        
                        
                    } else if (IS_RENO && dupACKcount > 3) {
                        // Reno: Fast Recovery: Inflate Window
                        cwnd += 1.0;
                        System.out.println("[Reno] Fast Recovery (DupACK). Inflating cwnd to " + cwnd);
                        logCwnd("RenoWindowInflation");
                    }
                }
			}
		}
	}
	
	// Helper for retransmission
	private void retransmitWindow() {
		System.out.println("Retransmitting window from base: " + base);
		java.util.List<Integer> sortedSeqs = new java.util.ArrayList<Integer>(sentPackets.keySet());
		java.util.Collections.sort(sortedSeqs);
		
		for (int seq : sortedSeqs) {
			TCP_PACKET pkt = sentPackets.get(seq);
			if (pkt != null) {
				udt_send(pkt);
			}
		}
		
		// Reset timer logic if needed
		if (retransTask != null) retransTask.cancel();
		retransTask = new UDT_RetransTask(this);
		timer.schedule(retransTask, 600, 600);
	}
	
	// GBN Timeout Handler
	public void onTimeout() {
		System.out.println("Timeout!");
		// Reno Timeout Handling (Same as Tahoe)
		ssthresh = Math.max(2, (int)cwnd / 2);
		cwnd = 1.0;
		dupACKcount = 0;
		isFastRecovery = false; // Reset State
		System.out.println("[Timeout] -> Slow Start. ssthresh -> " + ssthresh + ", cwnd -> " + cwnd);
		logCwnd("Timeout");
		
		// Retransmit
		retransmitWindow();
	}
	
	static class UDT_RetransTask extends java.util.TimerTask {
		private TCP_Sender sender;
		public UDT_RetransTask(TCP_Sender sender) {
			this.sender = sender;
		}
		@Override
		public void run() {
			sender.onTimeout();
		}
	}
	
	//接收到ACK报文：检查校验和，将确认号插入ack队列;NACK的确认号为－1；不需要修改
	public void recv(TCP_PACKET recvPack) {
		System.out.println("Receive ACK Number： "+ recvPack.getTcpH().getTh_ack());
		ackQueue.add(recvPack.getTcpH().getTh_ack());
	    System.out.println();	
	   
	    //处理ACK报文
	    waitACK();
	   
	}
	
}
