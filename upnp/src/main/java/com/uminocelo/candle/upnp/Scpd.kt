package com.uminocelo.candle.upnp

/** Builders for UPnP service descriptions (SCPDs): the actions a service answers and their state variables. */
object Scpd {
    /** `action("Play", "InstanceID" to "A_ARG_TYPE_InstanceID", out = …)`: arguments name their state variable. */
    fun action(name: String, vararg inArgs: Pair<String, String>, out: List<Pair<String, String>> = emptyList()): String {
        val args = inArgs.map { (arg, variable) -> argument(arg, "in", variable) } + out.map { (arg, variable) -> argument(arg, "out", variable) }
        return "<action><name>$name</name><argumentList>${args.joinToString("")}</argumentList></action>"
    }

    private fun argument(name: String, direction: String, variable: String) =
        "<argument><name>$name</name><direction>$direction</direction><relatedStateVariable>$variable</relatedStateVariable></argument>"

    fun variable(name: String, type: String, events: Boolean = false, allowed: List<String> = emptyList(), range: IntRange? = null): String {
        val values = when {
            range != null -> "<allowedValueRange><minimum>${range.first}</minimum><maximum>${range.last}</maximum><step>1</step></allowedValueRange>"
            allowed.isEmpty() -> ""
            else -> allowed.joinToString("", "<allowedValueList>", "</allowedValueList>") { "<allowedValue>$it</allowedValue>" }
        }
        return "<stateVariable sendEvents=\"${if (events) "yes" else "no"}\"><name>$name</name><dataType>$type</dataType>$values</stateVariable>"
    }

    fun document(actions: List<String>, variables: List<String>) = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<actionList>${actions.joinToString("")}</actionList>
<serviceStateTable>${variables.distinct().joinToString("")}</serviceStateTable>
</scpd>"""

    /** ConnectionManager:1 as both renderers and servers implement it: protocol info and the one implicit connection. */
    val CONNECTION_MANAGER: String = document(
        actions = listOf(
            action("GetProtocolInfo", out = listOf("Source" to "SourceProtocolInfo", "Sink" to "SinkProtocolInfo")),
            action("GetCurrentConnectionIDs", out = listOf("ConnectionIDs" to "CurrentConnectionIDs")),
            action(
                "GetCurrentConnectionInfo", "ConnectionID" to "A_ARG_TYPE_ConnectionID",
                out = listOf(
                    "RcsID" to "A_ARG_TYPE_RcsID", "AVTransportID" to "A_ARG_TYPE_AVTransportID",
                    "ProtocolInfo" to "A_ARG_TYPE_ProtocolInfo", "PeerConnectionManager" to "A_ARG_TYPE_ConnectionManager",
                    "PeerConnectionID" to "A_ARG_TYPE_ConnectionID", "Direction" to "A_ARG_TYPE_Direction",
                    "Status" to "A_ARG_TYPE_ConnectionStatus",
                ),
            ),
        ),
        variables = listOf(
            variable("SourceProtocolInfo", "string", events = true),
            variable("SinkProtocolInfo", "string", events = true),
            variable("CurrentConnectionIDs", "string", events = true),
            variable("A_ARG_TYPE_ConnectionID", "i4"),
            variable("A_ARG_TYPE_RcsID", "i4"),
            variable("A_ARG_TYPE_AVTransportID", "i4"),
            variable("A_ARG_TYPE_ProtocolInfo", "string"),
            variable("A_ARG_TYPE_ConnectionManager", "string"),
            variable("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")),
            variable("A_ARG_TYPE_ConnectionStatus", "string", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
        ),
    )
}
