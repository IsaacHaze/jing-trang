package com.thaiopensource.relaxng;

import org.xml.sax.SAXParseException;
import org.xml.sax.SAXException;
import org.xml.sax.Locator;
import org.xml.sax.XMLReader;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.helpers.LocatorImpl;

import org.relaxng.datatype.ValidationContext;

import java.util.Hashtable;

public class Validator implements ContentHandler {
  private final PatternBuilder builder;
  private Locator locator;
  private final XMLReader xr;
  private Pattern start;
  private Hashtable recoverPatternTable;
  private PatternMemo memo;
  private boolean hadError = false;
  private boolean collectingCharacters = false;
  private StringBuffer charBuf = new StringBuffer();
  private PrefixMapping prefixMapping = new PrefixMapping("xml", PatternReader.xmlURI, null);

  static private final class PrefixMapping implements ValidationContext {
    private final String prefix;
    private final String namespaceURI;
    private final PrefixMapping prev;

    PrefixMapping(String prefix, String namespaceURI, PrefixMapping prev) {
      this.prefix = prefix;
      this.namespaceURI = namespaceURI;
      this.prev = prev;
    }

    PrefixMapping getPrevious() {
      return prev;
    }

    public String resolveNamespacePrefix(String prefix) {
      PrefixMapping tem = this;
      do {
	if (tem.prefix.equals(prefix))
	  return tem.namespaceURI;
	tem = tem.prev;
      } while (tem != null);
      return null;
    }

    public String getBaseUri() {
      return null;
    }

    public boolean isUnparsedEntity(String name) {
      return false;
    }

    public boolean isNotation(String name) {
      return false;
    }

  }

  private void startCollectingCharacters() {
    if (!collectingCharacters) {
      collectingCharacters = true;
      charBuf.setLength(0);
    }
  }

  private void flushCharacters() throws SAXException {
    collectingCharacters = false;
    int len = charBuf.length();
    for (int i = 0; i < len; i++) {
      switch (charBuf.charAt(i)) {
      case ' ':
      case '\r':
      case '\t':
      case '\n':
	break;
      default:
	text();
	return;
      }
    }
  }

  public void startElement(String namespaceURI,
			   String localName,
			   String qName,
			   Attributes atts) throws SAXException {
    if (collectingCharacters)
      flushCharacters();

    Name name = new Name(namespaceURI, localName);
    if (!setMemo(memo.startTagOpenDeriv(name))) {
      PatternMemo next = memo.startTagOpenRecoverDeriv(name);
      if (!next.isNotAllowed())
        error("required_elements_missing");
      else {
        next = builder.getPatternMemo(builder.makeAfter(findElement(name), memo.getPattern()));
        error(next.isNotAllowed() ? "unknown_element" : "out_of_context_element", localName);
      }
      memo = next;
    }
    int len = atts.getLength();
    for (int i = 0; i < len; i++) {
      Name attName = new Name(atts.getURI(i), atts.getLocalName(i));

      if (!setMemo(memo.startAttributeDeriv(attName)))
	error("impossible_attribute_ignored", atts.getLocalName(i));
      else if (!setMemo(memo.dataDeriv(atts.getValue(i), prefixMapping))) {
        error("bad_attribute_value", atts.getLocalName(i));
        memo = memo.recoverAfter();
      }
    }
    if (!setMemo(memo.endAttributes())) {
      // XXX should specify which attributes
      error("required_attributes_missing");
      memo = memo.ignoreMissingAttributes();
    }
    if (memo.getPattern().getContentType() == Pattern.DATA_CONTENT_TYPE)
      startCollectingCharacters();
  }

  private PatternMemo fixAfter(PatternMemo p) {
    return builder.getPatternMemo(p.getPattern().applyForPattern(new ApplyAfterFunction(builder) {
      Pattern apply(Pattern p) {
        return builder.makeEmpty();
      }
    }));
  }

  public void endElement(String namespaceURI,
			 String localName,
			 String qName) throws SAXException {
    /* The tricky thing here is that the derivative may be notAllowed simply because the parent
       is notAllowed; we don't want to give an error in this case. */
    if (collectingCharacters) {
      collectingCharacters = false;
      if (!setMemo(memo.textOnly())) {
	error("only_text_not_allowed");
	memo = memo.recoverAfter();
	return;
      }
      String data = charBuf.toString();
      if (!setMemo(memo.dataDeriv(data, prefixMapping))) {
        PatternMemo next = memo.recoverAfter();
        boolean suppressError = false;
        if (!memo.isNotAllowed()) {
          if (!next.isNotAllowed()
              || fixAfter(memo).dataDeriv(data, prefixMapping).isNotAllowed())
            error("string_not_allowed");
        }
        memo = next;
      }
    }
    else if (!setMemo(memo.endTagDeriv())) {
      PatternMemo next = memo.recoverAfter();
      boolean suppressError = false;
      if (!memo.isNotAllowed()) {
        if (!next.isNotAllowed()
            || fixAfter(memo).endTagDeriv().isNotAllowed())
          error("unfinished_element");
      }
      memo = next;
    }
  }

  public void characters(char ch[], int start, int length) throws SAXException {
    if (collectingCharacters) {
      charBuf.append(ch, start, length);
      return;
    }
    for (int i = 0; i < length; i++) {
      switch (ch[start + i]) {
      case ' ':
      case '\r':
      case '\t':
      case '\n':
	break;
      default:
	text();
	return;
      }
    }
  }

  private void text() throws SAXException {
    if (!setMemo(memo.mixedTextDeriv()))
      error("text_not_allowed");
  }

  public void endDocument() { }

  public void setDocumentLocator(Locator loc) {
    locator = loc;
  }

  public void startDocument() throws SAXException {
    if (memo.isNotAllowed())
      error("schema_allows_nothing");
  }
  public void processingInstruction(String target, String date) { }
  public void skippedEntity(String name) { }
  public void ignorableWhitespace(char[] ch, int start, int len) { }
  public void startPrefixMapping(String prefix, String uri) {
    prefixMapping = new PrefixMapping(prefix, uri, prefixMapping);
  }
  public void endPrefixMapping(String prefix) {
    prefixMapping = prefixMapping.getPrevious();
  }

  public Validator(Pattern pattern, PatternBuilder builder, XMLReader xr) {
    this.builder = builder;
    this.xr = xr;
    this.memo = builder.getPatternMemo(pattern);
    this.start = pattern;
  }

  public boolean getValid() {
    return !hadError;
  }

  private void error(String key) throws SAXException {
    if (hadError && memo.isNotAllowed())
      return;
    hadError = true;
    ErrorHandler eh = xr.getErrorHandler();
    if (eh != null)
      eh.error(new SAXParseException(Localizer.message(key), locator));
  }

  private void error(String key, String arg) throws SAXException {
    if (hadError && memo.isNotAllowed())
      return;
    hadError = true;
    ErrorHandler eh = xr.getErrorHandler();
    if (eh != null)
      eh.error(new SAXParseException(Localizer.message(key, arg), locator));
  }

  private void error(String key, String arg1, String arg2, Locator loc) throws SAXException {
    if (hadError && memo.isNotAllowed())
      return;
    hadError = true;
    ErrorHandler eh = xr.getErrorHandler();
    if (eh != null)
      eh.error(new SAXParseException(Localizer.message(key, arg1, arg2),
				     loc));
  }

  private void error(String key, String arg1, String arg2) throws SAXException {
    if (hadError && memo.isNotAllowed())
      return;
    hadError = true;
    ErrorHandler eh = xr.getErrorHandler();
    if (eh != null)
      eh.error(new SAXParseException(Localizer.message(key, arg1, arg2),
				     locator));
  }

  /* Return false if m is notAllowed. */
  private boolean setMemo(PatternMemo m) {
    if (m.isNotAllowed())
      return false;
    else {
      memo = m;
      return true;
    }
  }

  private Pattern findElement(Name name) {
    if (recoverPatternTable == null)
     recoverPatternTable = new Hashtable();
    Pattern p = (Pattern)recoverPatternTable.get(name);
    if (p == null) {
      p = FindElementFunction.findElement(builder, name, start);
      recoverPatternTable.put(name, p);
    }
    return p;
  }
}
