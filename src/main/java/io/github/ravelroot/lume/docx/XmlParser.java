package io.github.ravelroot.lume.docx;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Lê XML com o StAX do próprio Java, em modo seguro: sem DTD, sem entidades externas e sem expandir entidades
 * declaradas no documento (sem XXE e sem "billion laughs").
 */
public final class XmlParser {

    private static final XMLInputFactory FACTORY = createFactory();

    private XmlParser() {
    }

    private static XMLInputFactory createFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    /** XML inválido (o leitor de DOCX transforma em falha definitiva com o nome da parte). */
    public static class XmlException extends RuntimeException {
        public XmlException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Devolve a raiz "#document" com a árvore inteira. */
    public static XmlNode parse(byte[] data) {
        XmlNode root = new XmlNode("#document");
        Deque<XmlNode> stack = new ArrayDeque<>();
        stack.push(root);
        XMLStreamReader reader = null;
        try {
            reader = FACTORY.createXMLStreamReader(new ByteArrayInputStream(data));
            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        XmlNode node = new XmlNode(reader.getLocalName());
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            node.attrs().putIfAbsent(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                        }
                        stack.peek().addChild(node);
                        stack.push(node);
                    }
                    case XMLStreamConstants.END_ELEMENT -> stack.pop();
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                            stack.peek().addText(reader.getText());
                    case XMLStreamConstants.ENTITY_REFERENCE ->
                            throw new XMLStreamException("Entidade não declarada: &" + reader.getLocalName() + ";");
                    default -> {
                        // instruções de processamento e comentários não interessam
                    }
                }
            }
            return root;
        } catch (XMLStreamException | RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            int detail = message.indexOf("Message: ");
            if (detail >= 0) {
                message = message.substring(detail + "Message: ".length());
            }
            int line = message.indexOf('\n');
            throw new XmlException(line >= 0 ? message.substring(0, line) : message, e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // nada a fazer
                }
            }
        }
    }
}
