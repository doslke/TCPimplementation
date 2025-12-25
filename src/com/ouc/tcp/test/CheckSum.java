package com.ouc.tcp.test;

import java.util.zip.CRC32;

import com.ouc.tcp.message.TCP_HEADER;
import com.ouc.tcp.message.TCP_PACKET;

public class CheckSum {
	
	/*计算TCP报文段校验和：只需校验TCP首部中的seq、ack和sum，以及TCP数据字段*/
	public static short computeChkSum(TCP_PACKET tcpPack) {
		int checkSum = 0;
		
		TCP_HEADER tcpH = tcpPack.getTcpH();
		// Sum seq, ack, and data
		checkSum += tcpH.getTh_seq();
		checkSum += tcpH.getTh_ack();
		
		int[] data = tcpPack.getTcpS().getData();
		if (data != null) {
			for (int i = 0; i < data.length; i++) {
				checkSum += data[i];
				// Handling overflow if necessary, but using CRC32 is better. 
				// However, to keep it simple and consistent with potential framework expectations:
				checkSum = (checkSum % 65535); // Simple wrap around
			}
		}
		
		return (short) checkSum;
	}
	
}
