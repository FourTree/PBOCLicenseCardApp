package com.icfcc.javacard.pboclicense;

import org.globalplatform.GPSystem;
import org.globalplatform.SecureChannel;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.Util;
import javacard.security.KeyPair;
import javacard.security.MessageDigest;
import javacard.security.RSAPrivateCrtKey;
import javacardx.crypto.Cipher;

/**
 * 
 * @author daor
 * 
 */

public class LicenseCardApp extends Applet {

	static private LicenseCardApp mApp = null;

	private RSAPrivateCrtKey mpricrtkey;
	private KeyPair keypair;
	static private short keyLength = (short) 0x0400;// 1024

	Cipher cipherEncPkcs;
	Cipher cipherDecPkcs;
	private byte[] counter;
	final static short counterlength = 0x0004;
	final static short TAG_COUNTER_in_C9 = (short) 0x85;

	SecureChannel secureChannel;
	boolean extAuthflag;

	private byte appstate;
	final static private byte APPSTATE_INIT = (byte) 0x00;
	final static private byte APPSTATE_SETP = (byte) 0x01;
	final static private byte APPSTATE_SETQ = (byte) 0x02;
	final static private byte APPSTATE_SETDP = (byte) 0x04;
	final static private byte APPSTATE_SETDQ = (byte) 0x08;
	final static private byte APPSTATE_SETCRT = (byte) 0x10;
	final static private byte APPSTATE_PERSONALIZED = APPSTATE_SETP | APPSTATE_SETQ | APPSTATE_SETDP | APPSTATE_SETDQ | APPSTATE_SETCRT;//个人化状态
	final static private byte APPSTATE_LOCKED = (byte) 0xFF ;//锁定

	private byte ext_Counter;//外部认证计数器
	final static private byte ext_MAX_Counter = (byte) 0x10;//最大外部认证失败次数


	/**
	 * fail result for find operation.
	 */
	final static private short TAG_NOT_FOUND = -1;

	public LicenseCardApp(byte[] bArray, short soffset) {
		counter = new byte[counterlength];// 需要预制使用次数
		ext_Counter = (byte)0;//外部认证次数复位
		Util.arrayCopy(bArray, soffset,this.counter, (short) 0, counterlength);

		cipherEncPkcs = Cipher.getInstance(Cipher.ALG_RSA_PKCS1, false);
		cipherDecPkcs = Cipher.getInstance(Cipher.ALG_RSA_PKCS1, false);

		appstate = APPSTATE_INIT;
		GenerateEmptyRSAKeyPair(keyLength);
	}

	/**
	 * 
	 * @param bArray
	 *            Len+instanceAID+len+privillege+len+C9parameter;
	 * @param bOffset
	 * @param bLength
	 *            e.g. install -i a0800000008182838485 -d -q C9#(850400000020)
	 *            a08000000080 a08000000081
	 */
	public static void install(byte[] bArray, short bOffset, byte bLength) {
		// GP-compliant JavaCard applet registration
		if (mApp != null) {
			ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
			return;
		}
		/* 获取INSTALL指令C9数据 */
		short offset = (short) (bOffset + bArray[bOffset]
				+ bArray[(short) (bOffset + 1 + bArray[bOffset])] + 2);// 取C9对应的长度的偏移量
		offset = findValueOffByTag(TAG_COUNTER_in_C9, bArray,
				(short) (offset + 1), (short) bArray[offset]);
		if (offset < 0) {
			ISOException.throwIt(ISO7816.SW_DATA_INVALID);
			return;
		}
		mApp = new LicenseCardApp(bArray, offset);
		mApp.register(bArray, (short) (bOffset + 1), bArray[bOffset]);
	}

	public void process(APDU apdu) {
		// Good practice: Return 9000 on SELECT
		if (selectingApplet()) {
			secureChannel = GPSystem.getSecureChannel();// 获取安全通道
			extAuthflag = false;
			return;
		}

		byte[] buf = apdu.getBuffer();
		short rlen = (short) 0;
		switch (buf[ISO7816.OFFSET_INS]) {
		case (byte) 0x50:
			apdu.setIncomingAndReceive();
			extAuthflag = false;
			ext_Counter ++;
			if(ext_Counter > ext_MAX_Counter){
				appstate = APPSTATE_LOCKED;
				ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
				return;
			}
			if ((byte) 0x80 == buf[ISO7816.OFFSET_CLA]) {
				apdu.setOutgoingAndSend(ISO7816.OFFSET_CDATA,
						secureChannel.processSecurity(apdu));
			} else {
				ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
				return;
			}
			break;
		case (byte) 0x82:
			apdu.setIncomingAndReceive();
			if ((byte) 0x84 != buf[ISO7816.OFFSET_CLA]) {
				ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
				return;
			}
			if (!(buf[ISO7816.OFFSET_P1] == (byte) 0x03)) {// 安全等级设置
				ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
				return;
			}
			apdu.setOutgoingAndSend(ISO7816.OFFSET_CDATA,
					secureChannel.processSecurity(apdu));
			extAuthflag = true;
			break;

		case (byte) 0xA0:// 私钥加密
			if (appstate != APPSTATE_PERSONALIZED) {
				ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
				return;
			}
			apdu.setIncomingAndReceive();
			if (!Decrease(counter, (short) 0, counterlength, (byte) 0x01)) {
				ISOException.throwIt((short) 0x6301);// 没有剩余次数
				return;
			}
		case (byte) 0xA1:// 查看剩余次数
			break;

		case (byte) 0xB1://
		case (byte) 0xB2://
		case (byte) 0xB3://
		case (byte) 0xB4://
		case (byte) 0xB5://
			if ((appstate == APPSTATE_PERSONALIZED) || (appstate == APPSTATE_LOCKED)) {
				ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
				return;
			}
			apdu.setIncomingAndReceive();
			if (!extAuthflag) {
				ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
				return;
			}
			if (0x04 == (buf[ISO7816.OFFSET_CLA] & 0x04)) {
				secureChannel.unwrap(buf, (short) 0,
						(short) (buf[ISO7816.OFFSET_LC] + 5));
			} else {
				ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
				return;
			}
			buf[0] = (byte) (buf[0] & 0xFC);
			break;
		default:
			extAuthflag = false;
			// good practice: If you don't know the INStruction, say so:
			ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
			return;
		}
		switch (buf[ISO7816.OFFSET_INS]) {
		case (byte) 0xA0:// 私钥加密
			cipherEncPkcs.init(mpricrtkey, Cipher.MODE_ENCRYPT);
			rlen = cipherEncPkcs.doFinal(buf, ISO7816.OFFSET_CDATA,
					(short) buf[ISO7816.OFFSET_LC], buf, (short) 0);
			apdu.setOutgoingAndSend((short) 0, rlen);
			break;

		case (byte) 0xA1:// 查看剩余次数
			Util.arrayCopy(counter, (short) 0, buf, (short) 0, counterlength);
			apdu.setOutgoingAndSend((short) 0, counterlength);
			break;

		case (byte) 0xB1:
			/** write private prime P */
			mpricrtkey.setP(buf, ISO7816.OFFSET_CDATA,
					(short) (0x00FF & buf[ISO7816.OFFSET_LC]));
			appstate |= APPSTATE_SETP;
			break;
		case (byte) 0xB2:
			/** write prime Q */
			mpricrtkey.setQ(buf, ISO7816.OFFSET_CDATA,
					(short) (0x00FF & buf[ISO7816.OFFSET_LC]));
			appstate |= APPSTATE_SETQ;
			break;
		case (byte) 0xB3:
			/** write prime exponent P */
			mpricrtkey.setDP1(buf, ISO7816.OFFSET_CDATA,
					(short) (0x00FF & buf[ISO7816.OFFSET_LC]));
			appstate |= APPSTATE_SETDP;
			break;
		case (byte) 0xB4:
			/** write prime exponent Q */
			mpricrtkey.setDQ1(buf, ISO7816.OFFSET_CDATA,
					(short) (0x00FF & buf[ISO7816.OFFSET_LC]));
			appstate |= APPSTATE_SETDQ;
			break;
		case (byte) 0xB5:
			/** write crt coefficient */
			mpricrtkey.setPQ(buf, ISO7816.OFFSET_CDATA,
					(short) (0x00FF & buf[ISO7816.OFFSET_LC]));
			appstate |= APPSTATE_SETCRT;
			break;
		}
	}

	/**
	 * 创建长度为 rsalen的空密钥对
	 * 
	 * @param rsalen
	 */
	public void GenerateEmptyRSAKeyPair(short rsalen) {
		keypair = new KeyPair(KeyPair.ALG_RSA_CRT, rsalen);// 创建公私钥对对象
		keypair.genKeyPair();// 生成公私钥
		mpricrtkey = (RSAPrivateCrtKey) keypair.getPrivate();
	}

	/**
	 * 
	 * @param mdata
	 * @param mOff
	 * @param mlength
	 * @return
	 */
	public static boolean Decrease(byte[] mdata, short mOff,
			final short mlength, byte mstep) {
		boolean c = false;// 是否借位成功
		final short step = (short) mstep;

		if ((short) (0x00FF & mdata[mlength - 1]) >= step) {// 不需要借位
			mdata[(short) (mlength - 1)] = (byte) ((short) mdata[(short) (mlength - 1)] - step);
			return true;
		}

		for (short i = (short) (mlength - 2); i >= 0; i--) {
			if ((short) (0x00FF & mdata[i]) > (short) 0) {
				mdata[i]--;// 借位成功
				c = true;
				for (short j = (short) (i + 1); j <= (short) (mlength - 2); j++) {
					mdata[j] = (byte) 0xFF;
				}
				break;
			} else {
				continue;
			}
		}
		if (c) {
			mdata[mlength - 1] = (byte) ((short) (0x00FF & mdata[mlength - 1])
					+ (short) 0x100 - step);
			return true;
		} else
			return false;
	}

	/**
	 * Get offset of value by specific tag in BER-TLV list.
	 * 
	 * @param tag
	 *            tag to be find.
	 * @param tlvList
	 *            buffer of tlv list.
	 * @param offset
	 *            offset of buffer.
	 * @param length
	 *            length of buffer.
	 * @return offset of value, TAG_NOT_FOUND if can't find. * e.g.
	 *         tag=0x85,list={81010202021122030199850400002000}，then return 12.
	 */
	public static short findValueOffByTag(short tag, byte[] tlvList,
			short offset, short length) {
		short i = offset;
		length += offset;

		while (i < length) {
			// tag
			short tagTemp = (short) (tlvList[i] & 0x00FF);
			if ((short) (tagTemp & 0x001F) == 0x001F) {
				i++;
				tagTemp <<= 8;
				tagTemp |= (short) (tlvList[i] & 0x00FF);
			}
			i++;

			// length
			if (tlvList[i] == (byte) 0x81) {
				i++;
			}
			i++;

			// value
			if (tag == tagTemp) {
				return i;
			}

			i += (tlvList[(short) (i - 1)] & 0x00FF);
		}
		return TAG_NOT_FOUND;
	}

}
