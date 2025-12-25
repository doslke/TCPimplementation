/***************************2.1: ACK/NACK*****************/
/***** Feng Hong; 2015-12-09******************************/
package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;

public class TCP_Receiver extends TCP_Receiver_ADT {
	
	private TCP_PACKET ackPack;	//回复的ACK报文段
	// int sequence=1;//用于记录当前待接收的包序号，注意包序号不完全是
	private int expectSequence = 1; // 2.2: 记录当前期待接收的包序号
	private int lastAck = -1;       // 2.2: 记录上一次成功确认的序号
		
	/*构造函数*/
	public TCP_Receiver() {
		super();	//调用超类构造函数
		super.initTCP_Receiver(this);	//初始化TCP接收端
	}

	@Override
	//接收到数据报：检查校验和，设置回复的ACK报文段
	public void rdt_recv(TCP_PACKET recvPack) {
		// 1. 检查校验码
		if(CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()) {
			
			int recvSeq = recvPack.getTcpH().getTh_seq();
			
			// 2. 检查序号是否符合期待 (解决重复包问题)
			if (recvSeq == expectSequence) {
				// 生成ACK报文段（设置确认号）
				tcpH.setTh_ack(recvSeq);
				ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
				tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
				
				// 回复ACK报文段
				reply(ackPack);			
				
				// 更新状态
				lastAck = recvSeq;
				// 假设数据载荷长度固定或可计算，更新期待的下一序号
				// 这里简单通过数据长度累加。注意：Sender中是 index * length + 1
				// 所以这里的 expectSequence 应该增加数据长度
				// 如果数据是 int[]，长度是 length * 4 bytes? 还是仅仅指个数？
				// 根据Sender: tcpH.setTh_seq(dataIndex * appData.length + 1);
				// 序列号是按 int 个数增加的 (假设 appData.length 是数组长度)
				int dataLen = recvPack.getTcpS().getData().length;
				expectSequence += dataLen;

				// 将接收到的正确有序的数据插入data队列，准备交付
				dataQueue.add(recvPack.getTcpS().getData());				
				// sequence++; // 不再使用简单的sequence++
				
				System.out.println("Receive SEQ: " + recvSeq + " (OK). Expected: " + (expectSequence - dataLen));
				
			} else if (recvSeq < expectSequence) {
				// 收到重复包 (可能是ACK丢失导致Sender重传)
				// 必须重传对应的ACK
				System.out.println("Receive Duplicate SEQ: " + recvSeq + ". Expected: " + expectSequence);
				
				tcpH.setTh_ack(recvSeq);
				ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
				tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
				reply(ackPack);
			} else {
				// 乱序包 (大于 expect)，在 Stop-and-Wait 不应出现，除非严重丢包
				System.out.println("Receive Out-of-Order SEQ: " + recvSeq + ". Expected: " + expectSequence);
				// 可以选择不回应，或者重发 lastAck
			}
			
		}else{
			// 校验和错误
			System.out.println("Recieve CheckSum Error. Seq: " + recvPack.getTcpH().getTh_seq());
			
			// 2.2: 不发送NACK，而是重发上一次正确的ACK (Duplicate ACK)
			if (lastAck != -1) {
				tcpH.setTh_ack(lastAck);
				ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
				tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
				reply(ackPack);
			}
			// 如果是第一个包就错了(lastAck==-1)，什么都不做，或者发送特定的初始ACK?
			// 这里选择什么都不做，因为没有Timer，Sender会死锁吗？
			// 不，Sender没有Timer，Sender也在死等ACK。
			// 如果Receiver不回ACK，Sender会一直卡在 waitACK 循环里。
			// 所以如果不回ACK，整个系统会挂起。
			// 为了防止死锁（因为没有Timer），我们还是得回点什么，让Sender知道不对。
			// 但Sender逻辑是: if (ack == currentSeq) OK else Retransmit.
			// 所以回 lastAck (-1) 也会触发重传。
			else {
				tcpH.setTh_ack(-1); // 特殊情况：第一个包就错，回-1触发重传
				ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
				tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
				reply(ackPack);
			}
		}
		
		System.out.println();
		
		
		//交付数据（每20组数据交付一次）
		if(dataQueue.size() == 20) 
			deliver_data();	
	}

	@Override
	//交付数据（将数据写入文件）；不需要修改
	public void deliver_data() {
		//检查dataQueue，将数据写入文件
		File fw = new File("recvData.txt");
		BufferedWriter writer;
		
		try {
			writer = new BufferedWriter(new FileWriter(fw, true));
			
			//循环检查data队列中是否有新交付数据
			while(!dataQueue.isEmpty()) {
				int[] data = dataQueue.poll();
				
				//将数据写入文件
				for(int i = 0; i < data.length; i++) {
					writer.write(data[i] + "\n");
				}
				
				writer.flush();		//清空输出缓存
			}
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	//回复ACK报文段
	public void reply(TCP_PACKET replyPack) {
		//设置错误控制标志
		tcpH.setTh_eflag((byte)1);	//eFlag=0，信道无错误
				
		//发送数据报
		client.send(replyPack);
	}
	
}
