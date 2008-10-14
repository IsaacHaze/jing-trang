package com.thaiopensource.relaxng.output.common;

import com.thaiopensource.relaxng.edit.AbstractVisitor;
import com.thaiopensource.relaxng.edit.NameNameClass;
import com.thaiopensource.relaxng.edit.ChoiceNameClass;
import com.thaiopensource.relaxng.edit.NameClass;
import com.thaiopensource.relaxng.edit.AnyNameNameClass;
import com.thaiopensource.relaxng.edit.NsNameNameClass;

import java.util.List;
import java.util.Vector;

public class NameClassSplitter extends AbstractVisitor {
  private final List<NameNameClass> names = new Vector<NameNameClass>();
  private boolean negative = false;

  static public List<NameNameClass> split(NameClass nc) {
    NameClassSplitter splitter = new NameClassSplitter();
    nc.accept(splitter);
    return splitter.names;
  }

  private NameClassSplitter() {
  }

  public Object visitName(NameNameClass nc) {
    if (!negative)
      names.add(nc);
    return null;
  }

  public Object visitChoice(ChoiceNameClass nc) {
    for (NameClass child : nc.getChildren())
      child.accept(this);
    return null;
  }

  public Object visitAnyName(AnyNameNameClass nc) {
    if (!negative) {
      NameClass except = nc.getExcept();
      if (except != null) {
        negative = true;
        except.accept(this);
        negative = false;
      }
    }
    return null;
  }

  public Object visitNsName(NsNameNameClass nc) {
    if (negative) {
      NameClass except = nc.getExcept();
      if (except != null) {
        int startIndex = names.size();
        negative = false;
        except.accept(this);
        negative = true;
        for (int i = startIndex, len = names.size(); i < len; i++) {
          if (!(names.get(i)).getNamespaceUri().equals(nc.getNs())) {
            names.remove(i);
            i--;
            len--;
          }
        }
      }
    }
    return null;
  }
}
