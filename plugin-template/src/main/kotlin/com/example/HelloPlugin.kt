package com.example

import com.hebe.plugin.api.HebePlugin
import com.hebe.plugin.api.PluginHost
import com.hebe.api.Tool

class HelloPlugin(wrapper: org.pf4j.PluginWrapper) : HebePlugin(wrapper) {
    override fun tools(host: PluginHost): List<Tool> = listOf(SayHelloTool(host))
}