package org.embulk.output.marketo_lead;

import org.embulk.base.restclient.RestClientOutputPluginBase;

public class MarketoLeadOutputPlugin
        extends RestClientOutputPluginBase<MarketoLeadOutputPluginDelegate.PluginTask>
{
    public MarketoLeadOutputPlugin()
    {
        super(MarketoLeadOutputPluginDelegate.PluginTask.class, new MarketoLeadOutputPluginDelegate());
    }
}
