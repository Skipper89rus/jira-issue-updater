package com.transas.jenkins.plugin.jiraissueupdater;

import java.util.Set;

import hudson.model.InvisibleAction;

/**
 * Remembers JIRA issues that need to be updated later,
 * when we get a successful build.
 */
public class CarryOverAction extends InvisibleAction
{
   private final Set<IssueEx> _issues;

   public CarryOverAction(Set<IssueEx> issues)
   {
      _issues = issues;
   }

   public Set<IssueEx> get()
   {
      return _issues;
   }
}
