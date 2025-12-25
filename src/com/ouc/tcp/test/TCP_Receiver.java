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
			
			// 4.0 GBN: 接收端逻辑
			// GBN接收端很简单：只接收符合 expectSequence 的包，否则丢弃（或重发上一个ACK）
			// 实际上 2.2 的接收端逻辑对于 GBN 也是适用的 (累积确认)
			
			if (recvSeq == expectSequence) {
				// 收到期待的包
				
				// 计算下一个期待的seq
				int dataLen = recvPack.getTcpS().getData().length;
				int nextExpect = expectSequence + dataLen;
				
				// 生成ACK报文段（设置确认号）
				// 注意：在GBN中，ack通常是"下一个期待的"，或者"已收到的最后一个"
				// 这里我们使用 "已收到的最后一个" (recvSeq)
				// 发送端会检查 ack >= base
				tcpH.setTh_ack(recvSeq);
				ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
				tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
				
				// 回复ACK报文段
				reply(ackPack);			
				
				// 更新状态
				lastAck = recvSeq;
				expectSequence = nextExpect;

				// 将接收到的正确有序的数据插入data队列，准备交付
				dataQueue.add(recvPack.getTcpS().getData());				
				
				System.out.println("Receive SEQ: " + recvSeq + " (OK). Next Expected: " + expectSequence);
				
			} else {
				// 乱序包或重复包：丢弃，重发上一个正确ACK (lastAck)
				System.out.println("Receive Unexpected SEQ: " + recvSeq + ". Expected: " + expectSequence);
				
				if (lastAck != -1) {
					tcpH.setTh_ack(lastAck);
					ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
					tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
					reply(ackPack);
				}
				// 如果是第一个包就错了(lastAck==-1)，不回应，让Sender超时重传
			}
			
		}else{
			// 校验和错误
			System.out.println("Recieve CheckSum Error. Seq: " + recvPack.getTcpH().getTh_seq());
			
			// GBN: 出错包直接丢弃，重发上一个正确ACK
			if (lastAck != -1) {
				tcpH.setTh_ack(lastAck);
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
		tcpH.setTh_eflag((byte)0);	//eFlag=0，信道无错误 (确保ACK能顺利返回，主要测试发送端的重传)
				
		//发送数据报
		client.send(replyPack);
	}
	
}
