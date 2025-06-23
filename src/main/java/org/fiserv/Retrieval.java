package org.fiserv;

public class Retrieval {

    private final static String DATA_RETRIEVAL = "<EFTRequest><RequestType>0003</RequestType><TransactionType>Retrieval</TransactionType><Amount></Amount><MerchantRef></MerchantRef><Currency>1</Currency><OriginalTxnType>0</OrginalTxnType></EFTRequest>";
    private String currency;
    private String merchanRef;
    private String amount;

    public Retrieval(String _amount, String _merchanRef, String _currency )
    {
        amount = _amount;
        merchanRef = _merchanRef;
        currency = _currency;
    }

    public String getAmount()
    {
        return amount;
    }

    public String getCurrency()
    {
        return currency;
    }

    public String getMerchanRef()
    {
        return merchanRef;
    }
    public String buildAppMsg()
    {
        XmlTag xmlTag = new XmlTag(DATA_RETRIEVAL);
        xmlTag.writeTag("BaseAmount", amount);
        xmlTag.writeTag("MerchantRef", merchanRef);
        xmlTag.writeTag("BaseCurrency", currency);
        return xmlTag.getXmlMsgString();
    }
}
