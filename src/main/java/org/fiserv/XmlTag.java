package org.fiserv;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.io.StringReader;
import org.xml.sax.InputSource;

public class XmlTag {

    private String xmlMsgString;
    public XmlTag(String _xmlMsgString)
    {
        xmlMsgString = _xmlMsgString;
    }

    public String readTagValue (String tagName)
    {
//        Element element = null;
        xmlMsgString = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><EFTRequest><RequestType>0003</RequestType><TransactionType>Purchase</TransactionType><MerchantRef>170120250503081154</MerchantRef><BaseCurrency>HKD</BaseCurrency><BaseAmount>100</BaseAmount><BaseAmountMinorUnit>2</BaseAmountMinorUnit><CardType>0</CardType><CashierID>01134</CashierID><WorkStationID>CLHKIAPO-1043</WorkStationID></EFTRequest>";

        try {
            // Parse XML string into a Document

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlMsgString)));

            // Normalize XML structure (optional but recommended)
   //         doc.getDocumentElement().normalize();
            // Get all elements with the specified tag
            NodeList nodeList = doc.getElementsByTagName(tagName);
            // Iterate through the results and print text content
            //for (int i = 0; i < nodeList.getLength(); i++) {
            //    element = (Element) nodeList.item(i);
            //}
            return nodeList.item(0).getTextContent();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void writeTag(String tagName, String newValue) {
        try {
            // Parse the XML string into a Document
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xmlMsgString.getBytes()));
            doc.getDocumentElement().normalize();

            // Find the first occurrence of the tag
            NodeList nodeList = doc.getElementsByTagName(tagName);
            if (nodeList.getLength() == 0) {
                // Tag not found - exit without changes
                return;
            }
            Element element = (Element) nodeList.item(0);
            // Update the element's content
            element.setTextContent(newValue);

            // Convert the updated Document back to a string
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));

            // Update the stored XML string
            xmlMsgString = writer.toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getXmlMsgString ()
    {
        return xmlMsgString;
    }
}