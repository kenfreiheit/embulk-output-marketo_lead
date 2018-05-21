Embulk::JavaPlugin.register_output(
  "marketo_lead", "org.embulk.output.marketo_lead.MarketoLeadOutputPlugin",
  File.expand_path('../../../../classpath', __FILE__))
