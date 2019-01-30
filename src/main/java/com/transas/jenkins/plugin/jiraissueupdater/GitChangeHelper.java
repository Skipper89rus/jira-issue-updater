package com.transas.jenkins.plugin.jiraissueupdater;

import hudson.plugins.git.GitChangeSet;
import hudson.scm.ChangeLogSet.Entry;

public class GitChangeHelper
{

   // getMsg для ChangeLogSet.Entry возвращает только title коммента,
   // нам нужно, чтобы возвращался полный коммент, который доступен только в GitChangeSet.
   public static String getMsg(Entry change)
   {
      if (change instanceof GitChangeSet)
      {
         return ((GitChangeSet) change).getComment();
      }
      return change.getMsg();
   }

   // Хак!!! Хочется получить нормальный userId.
   // change.getAuthor добавляет сетит в него данные из почтового ящика.
   public static String getAuthorId(Entry change)
   {
      if (change instanceof GitChangeSet)
      {
         GitChangeSet c = (GitChangeSet) change;
         return c.findOrCreateUser(c.getAuthorName(), c.getAuthorName() + "@transas.com", false).getId();
      }
      return change.getAuthor().getId();
   }
}
