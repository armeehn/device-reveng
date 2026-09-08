package com.ripostelabs.carlauncher.carlib

import org.json.JSONArray
import org.json.JSONObject

/**
 * AccessoryConfig — what is wired up, what the choreographies are, and what starts them.
 *
 * Stored as one JSON string in the launcher's settings, edited off the car and pasted in, the
 * way the wheel-key map is. The codec lives here, beside the domain, so the shape is tested
 * without a device.
 *
 * ```json
 * {
 *   "baseUrl": "http://accessories.car",
 *   "accessories": [
 *     {"id": "bar",     "name": "Light bar", "kind": "switch"},
 *     {"id": "antenna", "name": "Antenna",   "kind": "level"}
 *   ],
 *   "sequences": [
 *     {"id": "stow", "name": "Stow antenna",
 *      "steps": [{"accessory": "antenna", "level": 0, "holdMs": 1500},
 *                {"accessory": "antenna", "power": "off"}]}
 *   ],
 *   "triggers": [
 *     {"id": "rev", "name": "Work lights", "on": "reverse", "sequence": "worklights"}
 *   ]
 * }
 * ```
 *
 * ── Malformed input degrades, it does not crash the HOME app ────────────────────────────────────
 * A bad blob yields an empty config and a list of what was dropped and why. A step that names an
 * accessory nobody declared is dropped, not kept as a step that will be rejected at runtime; a
 * trigger naming an unknown sequence or an unknown event is dropped likewise. The first
 * declaration of an id wins, so a duplicate cannot silently redefine a light.
 */
data class AccessoryConfig(
    val baseUrl: String = "",
    val accessories: List<Accessory> = emptyList(),
    val sequences: List<AccessorySequence> = emptyList(),
    val triggers: List<TriggerSpec> = emptyList(),

    /** Human-readable reasons for anything the parser refused. Empty on a clean parse. */
    val problems: List<String> = emptyList(),
) {

    /** A trigger as configured. Bound to a live [AccessoryTrigger] by [toTriggers]. */
    data class TriggerSpec(val id: String, val name: String, val on: TriggerEvent, val sequenceId: String)

    enum class TriggerEvent(val wire: String) {
        REVERSE("reverse"),
        ANY_DOOR_OPEN("anyDoorOpen"),
        ALL_DOORS_SHUT("allDoorsShut"),
    }

    val isEmpty: Boolean get() = accessories.isEmpty() && sequences.isEmpty()

    /** Live triggers, each bound to its sequence and its snapshot condition. */
    fun toTriggers(): List<AccessoryTrigger> = triggers.mapNotNull { spec ->
        val sequence = sequences.firstOrNull { it.id == spec.sequenceId } ?: return@mapNotNull null
        val condition: (VehicleSnapshot, Long) -> Boolean? = when (spec.on) {
            TriggerEvent.REVERSE -> AccessoryTrigger::reverse
            TriggerEvent.ANY_DOOR_OPEN -> AccessoryTrigger::anyDoorOpen
            TriggerEvent.ALL_DOORS_SHUT -> AccessoryTrigger::allDoorsShut
        }
        AccessoryTrigger(spec.id, spec.name, sequence, condition)
    }

    fun serialize(): String {
        val root = JSONObject()
        root.put(KEY_BASE_URL, baseUrl)
        root.put(KEY_ACCESSORIES, JSONArray().also { arr ->
            accessories.forEach { a ->
                arr.put(JSONObject().put(KEY_ID, a.id).put(KEY_NAME, a.name).put(KEY_KIND, a.kind.wire()))
            }
        })
        root.put(KEY_SEQUENCES, JSONArray().also { arr ->
            sequences.forEach { s ->
                arr.put(JSONObject().put(KEY_ID, s.id).put(KEY_NAME, s.name).put(KEY_STEPS, JSONArray().also { steps ->
                    s.steps.forEach { st ->
                        val o = JSONObject().put(KEY_ACCESSORY, st.accessoryId).put(KEY_HOLD, st.holdMs)
                        when (val c = st.command) {
                            is AccessoryCommand.SetPower -> o.put(KEY_POWER, if (c.power == Power.ON) WIRE_ON else WIRE_OFF)
                            is AccessoryCommand.SetLevel -> o.put(KEY_LEVEL, c.level)
                        }
                        steps.put(o)
                    }
                }))
            }
        })
        root.put(KEY_TRIGGERS, JSONArray().also { arr ->
            triggers.forEach { t ->
                arr.put(JSONObject().put(KEY_ID, t.id).put(KEY_NAME, t.name).put(KEY_ON, t.on.wire).put(KEY_SEQUENCE, t.sequenceId))
            }
        })
        return root.toString()
    }

    companion object {
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_ACCESSORIES = "accessories"
        private const val KEY_SEQUENCES = "sequences"
        private const val KEY_TRIGGERS = "triggers"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_KIND = "kind"
        private const val KEY_STEPS = "steps"
        private const val KEY_ACCESSORY = "accessory"
        private const val KEY_HOLD = "holdMs"
        private const val KEY_POWER = "power"
        private const val KEY_LEVEL = "level"
        private const val KEY_ON = "on"
        private const val KEY_SEQUENCE = "sequence"
        private const val WIRE_ON = "on"
        private const val WIRE_OFF = "off"
        private const val WIRE_SWITCH = "switch"
        private const val WIRE_LEVEL = "level"

        private fun AccessoryKind.wire() = if (this == AccessoryKind.SWITCH) WIRE_SWITCH else WIRE_LEVEL

        /** Parse, dropping what cannot be used and saying so. Never throws; null or junk is empty. */
        fun parse(json: String?): AccessoryConfig {
            val problems = mutableListOf<String>()
            val root = json?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return if (json.isNullOrBlank()) AccessoryConfig() else AccessoryConfig(problems = listOf("not a JSON object"))

            val accessories = mutableListOf<Accessory>()
            root.optJSONArray(KEY_ACCESSORIES)?.objects()?.forEach { o ->
                val id = o.optString(KEY_ID, "")
                val kind = when (o.optString(KEY_KIND, "")) {
                    WIRE_SWITCH -> AccessoryKind.SWITCH
                    WIRE_LEVEL -> AccessoryKind.LEVEL
                    else -> null
                }
                when {
                    id.isBlank() -> problems += "accessory without an id"
                    kind == null -> problems += "accessory '$id': unknown kind '${o.optString(KEY_KIND)}'"
                    accessories.any { it.id == id } -> problems += "accessory '$id': duplicate id, first one kept"
                    else -> accessories += Accessory(id, o.optString(KEY_NAME, id), kind)
                }
            }
            val known = accessories.map { it.id }.toSet()

            val sequences = mutableListOf<AccessorySequence>()
            root.optJSONArray(KEY_SEQUENCES)?.objects()?.forEach { o ->
                val id = o.optString(KEY_ID, "")
                if (id.isBlank()) { problems += "sequence without an id"; return@forEach }
                if (sequences.any { it.id == id }) { problems += "sequence '$id': duplicate id, first one kept"; return@forEach }

                val steps = o.optJSONArray(KEY_STEPS)?.objects()?.mapNotNull { s ->
                    val target = s.optString(KEY_ACCESSORY, "")
                    val command: AccessoryCommand? = when {
                        s.has(KEY_LEVEL) -> s.optInt(KEY_LEVEL, -1).takeIf { it in 0..100 }?.let { AccessoryCommand.SetLevel(it) }
                        s.optString(KEY_POWER) == WIRE_ON -> AccessoryCommand.SetPower(Power.ON)
                        s.optString(KEY_POWER) == WIRE_OFF -> AccessoryCommand.SetPower(Power.OFF)
                        else -> null
                    }
                    val hold = s.optLong(KEY_HOLD, 0L)
                    when {
                        target !in known -> { problems += "sequence '$id': step names unknown accessory '$target'"; null }
                        command == null -> { problems += "sequence '$id': step for '$target' has no valid power or level"; null }
                        hold < 0 -> { problems += "sequence '$id': negative hold"; null }
                        else -> SequenceStep(target, command, hold)
                    }
                } ?: emptyList()

                if (steps.isEmpty()) problems += "sequence '$id': no usable steps, dropped"
                else sequences += AccessorySequence(id, o.optString(KEY_NAME, id), steps)
            }
            val knownSeq = sequences.map { it.id }.toSet()

            val triggers = mutableListOf<TriggerSpec>()
            root.optJSONArray(KEY_TRIGGERS)?.objects()?.forEach { o ->
                val id = o.optString(KEY_ID, "")
                val event = TriggerEvent.entries.firstOrNull { it.wire == o.optString(KEY_ON, "") }
                val seq = o.optString(KEY_SEQUENCE, "")
                when {
                    id.isBlank() -> problems += "trigger without an id"
                    event == null -> problems += "trigger '$id': unknown event '${o.optString(KEY_ON)}'"
                    seq !in knownSeq -> problems += "trigger '$id': unknown sequence '$seq'"
                    triggers.any { it.id == id } -> problems += "trigger '$id': duplicate id, first one kept"
                    else -> triggers += TriggerSpec(id, o.optString(KEY_NAME, id), event, seq)
                }
            }

            return AccessoryConfig(root.optString(KEY_BASE_URL, ""), accessories, sequences, triggers, problems)
        }

        private fun JSONArray.objects(): List<JSONObject> =
            (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
