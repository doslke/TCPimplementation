/***************************2.1: ACK/NACK
**************************** Feng Hong; 2015-12-09*/

package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Sender extends TCP_Sender_ADT {
	
	private TCP_PACKET tcpPack;	//待发送的TCP数据报
	private volatile int flag = 0;
	
	// RDT 4.0: GBN Variables
	private int windowSize = 5; // 窗口大小 N
	private int base = 1;       // 基序号
	private int nextSeqNum = 1; // 下一个序号
	// GBN需要缓存已发送但未确认的包，这里简单使用 List 或 Map
	// 由于序号是 index * length + 1，不是连续整数，用 Map 更方便
	// 或者直接缓存 TCP_PACKET
	private java.util.concurrent.ConcurrentHashMap<Integer, TCP_PACKET> sentPackets = new java.util.concurrent.ConcurrentHashMap<Integer, TCP_PACKET>();
	private UDT_Timer timer;
	private UDT_RetransTask retransTask;
	
	/*构造函数*/
	public TCP_Sender() {
		super();	//调用超类构造函数
		super.initTCP_Sender(this);		//初始化TCP发送端
		timer = new UDT_Timer();
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
		
		// 必须等待窗口有空位
		// 在真实GBN中，上层调用应该被拒绝或缓存。这里我们简单用 while 循环阻塞
		while (nextSeqNum >= base + windowSize * 100) {
			try { Thread.sleep(10); } catch (InterruptedException e) {}
		}
		
		//生成TCP数据报（设置序号和数据字段/校验和),注意打包的顺序
		tcpH.setTh_seq(currentSeq);
		tcpS.setData(appData);
		tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);		
				
		tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
		tcpPack.setTcpH(tcpH);
		
		// 缓存包
		sentPackets.put(currentSeq, tcpPack);
		
		//发送TCP数据报
		udt_send(tcpPack);
		// flag = 0; // GBN不再使用简单的 flag 阻塞
		
		// 更新 nextSeqNum
		// 注意：这里的 nextSeqNum 应该等于 currentSeq + dataLen
		// 但 rdt_send 的 dataIndex 参数决定了 seq。
		// 我们假设调用是顺序的。
		nextSeqNum = currentSeq + dataLen;
		
		// 如果是基序号包，启动定时器
		if (base == currentSeq) {
			if (retransTask != null) retransTask.cancel();
			retransTask = new UDT_RetransTask(this);
			timer.schedule(retransTask, 1000, 1000);
		}
		
		// 不再阻塞等待 ACK，立即返回允许发送下一个
	}
	
	@Override
	//不可靠发送：将打包好的TCP数据报通过不可靠传输信道发送；仅需修改错误标志
	public void udt_send(TCP_PACKET stcpPack) {
		//设置错误控制标志
		tcpH.setTh_eflag((byte)1);	// 4.0: 测试丢包和错误	
		//System.out.println("to send: "+stcpPack.getTcpH().getTh_seq());				
		//发送数据报
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
				// 移动窗口
				// base = currentAck + 100; // 假设长度固定 100
				// 更精确做法：从 sentPackets 中移除 <= currentAck 的包，并更新 base
				
				// 移除已确认的包
				java.util.Iterator<Integer> it = sentPackets.keySet().iterator();
				while(it.hasNext()){
					int seq = it.next();
					if(seq <= currentAck){
						it.remove();
					}
				}
				
				base = currentAck + 100; // 假设数据长度是 100
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
					timer.schedule(retransTask, 1000, 1000);
				}
			}
			// 否则是重复ACK，忽略 (GBN不处理重复ACK触发快速重传，只靠超时)
		}
	}
	
	// GBN Timeout Handler
	public void onTimeout() {
		System.out.println("Timeout! Resending window from base: " + base);
		// 重传 [base, nextSeqNum-1] 所有包
		// 遍历 sentPackets 重新发送
		// 注意顺序，应该按 seq 排序发送
		java.util.List<Integer> sortedSeqs = new java.util.ArrayList<Integer>(sentPackets.keySet());
		java.util.Collections.sort(sortedSeqs);
		
		for (int seq : sortedSeqs) {
			TCP_PACKET pkt = sentPackets.get(seq);
			if (pkt != null) {
				// 注意：重传时也需要调用 udt_send，这会再次应用 eFlag 7
				// 可能会导致重传包再次丢失，这是正常的 GBN 行为
				udt_send(pkt);
			}
		}
		
		// 关键修复：
		// 必须显式重启定时器，因为 TimerTask 是一次性的（除非 scheduleAtFixedRate，但这里我们用的是 schedule）
		// 而且我们希望在"重传"动作之后重新开始计时
		// 原来的代码 rely on "schedule(task, 1000, 1000)" 周期性执行
		// 但是 UDT_Timer 是 java.util.Timer 吗？是的。
		// schedule(task, delay, period) 会重复执行。
		// 问题可能在于：如果 base 包丢失，一直没收到 ACK，定时器周期性触发重传。
		// 接收端一直在收乱序包 (Receive Out-of-Order)，说明发送端发了后续包，但没发 base 包？
		// 或者 base 包一直丢？
		
		// 另一种可能是：base 包确实发了，但接收端没收到（丢包），于是接收端一直在报 Out-of-Order (针对后续包)。
		// 发送端超时后重传 base...nextSeqNum。
		// 如果重传的 base 又丢了...
		
		// 还有一种可能：udt_send 里设置了 eFlag=7。
		// 如果网络层丢包率很高，或者逻辑有问题。
		
		// 让我们检查一下 waitACK 中是否有逻辑漏洞导致 timer 被错误 cancel。
		// 只有当 base == nextSeqNum (窗口空) 时才 cancel。
		// 如果窗口不空，timer 应该一直跑。
		
		// 还有一个潜在问题：并发修改异常。
		// sentPackets 是 ConcurrentHashMap，但在遍历时 udt_send 可能会被其他线程（waitACK）修改吗？
		// waitACK 可能会 remove。ConcurrentHashMap iterator 是弱一致的，不会抛异常，但可能读不到最新。
		// 这里是拷贝了 keySet 到 ArrayList，所以应该安全。
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
