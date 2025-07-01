package org.fiserv;

import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;

public class AppMsg {
    private final static char START = 'R';
    private final static String xmlPreamble = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private final static int LEN_XML_PREAMBLE = xmlPreamble.length() +4;
    public final static String DATA_ACKNOWLEDGE = "<EFTAcknowledgement><AcknowledgementType>0003</AcknowledgementType></EFTAcknowledgement>";
    public final static int MSG_ACK = 1;
    public final static int MSG_RTV = 2;
    public final static char OP_QST = 'Q';
    public final static char OP_ANS = 'A';
    public final static char OP_ACK = 'K';
    public final static char OP_NAK = 'N';

    private int msgType;
    private char opType;
    private String orgMessage;
    private byte[] storeMessage;

    private String rtvMessage;
    public AppMsg( int _msgType, char _opType, String _orgMessage )
    {
        msgType = _msgType;
        opType = _opType;
        orgMessage = _orgMessage;
    }

    public AppMsg( int _msgType, char _opType )
    {
        msgType = _msgType;
        opType = _opType;
    }

    public AppMsg(){}

    public void saveMsg (byte[] _svMsg)
    {
        storeMessage = _svMsg;
    }

    public byte[] getMsg()
    {
        return storeMessage;
    }

    public String packMessage()
    {
        String msgOut=null;

        switch (msgType) {
            case MSG_ACK:
                msgOut = Character.toString(START) + Character.toString(opType) + len5Digit(xmlPreamble +DATA_ACKNOWLEDGE) + xmlPreamble + DATA_ACKNOWLEDGE;
                break;
            case MSG_RTV:
                XmlTag xmlTag = new XmlTag(orgMessage.substring(LEN_XML_PREAMBLE));
                String baseAmount = xmlTag.readTagValue("BaseAmount");
                String merchantRef = xmlTag.readTagValue("MerchantRef");
                String baseCurrency = xmlTag.readTagValue("BaseCurrency");
                    // initialize xmlTag object with original payment message sent to A920
                Retrieval retrieval = new Retrieval( baseAmount, merchantRef,baseCurrency);
                rtvMessage = retrieval.buildAppMsg();
                msgOut = Character.toString(START) + Character.toString(opType) + len5Digit( xmlPreamble + rtvMessage) + xmlPreamble + rtvMessage;
                break;
            default:
                msgOut = null;
                break;
        }
        return msgOut;
    }
    private String len5Digit( String data )
    {
        return String.format("%05d", data.length());
    }
}
