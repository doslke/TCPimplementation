/***************************2.1: ACK/NACK
**************************** Feng Hong; 2015-12-09*/

package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
// import com.ouc.tcp.client.UDT_RetransTask; // 移除未找到的类
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Sender extends TCP_Sender_ADT {
	
	private TCP_PACKET tcpPack;	//待发送的TCP数据报
	private volatile int flag = 0;
	private UDT_Timer timer;	// 3.0: 定义定时器
	private UDT_RetransTask retransTask; // 3.0: 重传任务
	
	/*构造函数*/
	public TCP_Sender() {
		super();	//调用超类构造函数
		super.initTCP_Sender(this);		//初始化TCP发送端
		timer = new UDT_Timer();		// 3.0: 创建定时器对象
	}
	
	@Override
	//可靠发送（应用层调用）：封装应用层数据，产生TCP数据报；
	public void rdt_send(int dataIndex, int[] appData) {
		
		//生成TCP数据报（设置序号和数据字段/校验和),注意打包的顺序
		tcpH.setTh_seq(dataIndex * appData.length + 1);//包序号设置为字节流号：
		tcpS.setData(appData);
		tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);		
				
		tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
		tcpPack.setTcpH(tcpH);
		
		//发送TCP数据报
		udt_send(tcpPack);
		flag = 0;
		
		// 3.0: 启动定时器，开始倒计时
		// 每次发送新包时重置并启动定时器
		if (retransTask != null) {
			retransTask.cancel(); // 取消旧任务
		}
		retransTask = new UDT_RetransTask(this, tcpPack);
		timer.schedule(retransTask, 1000, 1000); // 3.0: 1秒后超时，若未取消则每1秒重试
		
		//等待ACK报文
		//waitACK();
		while (flag==0);
	}
	
	@Override
	//不可靠发送：将打包好的TCP数据报通过不可靠传输信道发送；仅需修改错误标志
	public void udt_send(TCP_PACKET stcpPack) {
		//设置错误控制标志
		tcpH.setTh_eflag((byte)7);		
		//System.out.println("to send: "+stcpPack.getTcpH().getTh_seq());				
		//发送数据报
		client.send(stcpPack);
	}
	
	@Override
	// waitACK: 循环检查ACK队列。
	// 3.0版本：如果收到重复ACK (不等于当前Seq)，则重传。如果超时，UDT_RetransTask 会被触发。
	public void waitACK() {
		//循环检查ackQueue
		//循环检查确认号对列中是否有新收到的ACK		
		if(!ackQueue.isEmpty()){
			int currentAck=ackQueue.poll();
			// System.out.println("CurrentAck: "+currentAck);
			if (currentAck == tcpPack.getTcpH().getTh_seq()){
				System.out.println("Clear: "+tcpPack.getTcpH().getTh_seq());
				
				// 3.0: 收到正确ACK，关闭定时器
				if (retransTask != null) {
					retransTask.cancel();
					retransTask = null;
				}
				
				flag = 1;
				//break;
			}else{
				// 收到旧的ACK (Duplicate ACK)，认为是NACK，触发重传
				System.out.println("Retransmit (DupACK): "+tcpPack.getTcpH().getTh_seq());
				
				// 3.0: 重传时重启定时器
				if (retransTask != null) {
					retransTask.cancel();
				}
				udt_send(tcpPack);
				retransTask = new UDT_RetransTask(this, tcpPack);
				timer.schedule(retransTask, 1000, 1000);
				
				flag = 0;
			}
		}
	}
	
	// 3.0: 超时处理函数。当定时器超时时，UDT_Timer 会调用此方法 (通过 UDT_RetransTask)
	// 注意：因为使用了 TimerTask，实际回调逻辑在 UDT_RetransTask.run() 中，
	// 我们可以在 run() 中调用 sender.onTimeout() 或者直接调用 udt_send
	// 为了代码结构清晰，我们让 UDT_RetransTask 回调这个方法
	public void onTimeout() {
		System.out.println("Timeout: "+tcpPack.getTcpH().getTh_seq());
		// 超时重传
		// if (flag == 0) // 在 TimerTask 中判断更安全，或者这里判断
		udt_send(tcpPack);
		// schedule 是循环执行的，所以不需要这里重启，除非我们用的是一次性 schedule
		// 如果用 schedule(task, delay)，则需要重启。
		// 如果用 schedule(task, delay, period)，则会自动重复。
		// 这里假设我们使用周期性 schedule，或者每次 timeout 后重置。
		// 简单起见，使用周期性 schedule。
	}
	
	// 内部类或外部类定义 UDT_RetransTask
	static class UDT_RetransTask extends java.util.TimerTask {
		private TCP_Sender sender;
		private TCP_PACKET packet;
		
		public UDT_RetransTask(TCP_Sender sender, TCP_PACKET packet) {
			this.sender = sender;
			this.packet = packet;
		}
		
		@Override
		public void run() {
			// 回调发送端
			sender.onTimeout();
		}
	}

	@Override
	//接收到ACK报文：检查校验和，将确认号插入ack队列;NACK的确认号为－1；不需要修改
	public void recv(TCP_PACKET recvPack) {
		System.out.println("Receive ACK Number： "+ recvPack.getTcpH().getTh_ack());
		ackQueue.add(recvPack.getTcpH().getTh_ack());
	    System.out.println();	
	   
	    //处理ACK报文
	    waitACK();
	   
	}
	
}
