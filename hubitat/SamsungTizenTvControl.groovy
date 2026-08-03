/*
 * Tizen Samsung TV driver for Hubitat.
 *
 * Power on/off (idempotent, with WOL fallback when unreachable), HDMI1
 * input switch, and raw remote-key passthrough, talking directly to the
 * TV's local REST status API (:8001) and remote-control websocket
 * (:8002) - no SmartThings/cloud dependency.
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION = '0.1.0'
@Field static final String KEY_POWER = 'KEY_POWER'
@Field static final String KEY_SOURCE = 'KEY_SOURCE'
@Field static final String KEY_DOWN = 'KEY_DOWN'
@Field static final String KEY_ENTER = 'KEY_ENTER'

metadata {
    definition(
        name: 'Samsung Tizen TV Control',
        namespace: 'hugoh',
        author: 'Hugo Haas',
        importUrl: 'https://github.com/hugoh/samsung-tizen-tv-control/blob/main/hubitat/SamsungTizenTvControl.groovy'
    ) {
        capability 'Switch'
        capability 'Actuator'
        capability 'Refresh'

        command 'inputHdmi1'
        command 'sendKey', ['string']
    }

    preferences {
        input name: 'deviceIp', type: 'text', title: 'TV IP address', required: true
        input name: 'tvWsToken', type: 'text',
            title: 'Pairing token (auto-populated after first pairing)', required: false
        input name: 'wolMac', type: 'text',
            title: 'MAC address for WOL (optional override, e.g. AABBCCDDEEFF)', required: false
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: false
    }
}

void installed() {
    updated()
}

void updated() {
    log.info("updated: deviceIp=${deviceIp}, driverVersion=${DRIVER_VERSION}")
    try {
        httpGet([uri: "http://${deviceIp}:8001/api/v2/", timeout: 5]) { resp ->
            String mac = resp.data.device.wifiMac
            if (mac) {
                String dni = mac.replaceAll(':', '').toUpperCase()
                if (device.deviceNetworkId != dni) {
                    device.deviceNetworkId = dni
                }
            }
        }
    } catch (Exception ex) {
        log.warn("updated: could not reach TV to fetch MAC (${ex.message})")
    }
}

boolean sendWol() {
    String mac = wolMac ?: device.deviceNetworkId
    if (!mac) {
        log.warn('sendWol: no MAC address known - run updated() while the TV is reachable first, ' +
            'or set the wolMac preference')
        return false
    }
    hubitat.device.HubAction wol = new hubitat.device.HubAction(
        "wake on lan ${mac}",
        hubitat.device.Protocol.LAN,
        null)
    sendHubCommand(wol)
    true
}

void refresh() {
    sendEvent(name: 'switch', value: getPowerState() == 'on' ? 'on' : 'off')
}

String getPowerState() {
    String result = queryPowerStateOnce()
    if (result == null) {
        pauseExecution(1000)
        result = queryPowerStateOnce()
    }
    result ?: 'unreachable'
}

private String queryPowerStateOnce() {
    try {
        String result = 'off'
        httpGet([uri: "http://${deviceIp}:8001/api/v2/", timeout: 5]) { resp ->
            result = resp.data.device.PowerState == 'on' ? 'on' : 'off'
        }
        return result
    } catch (Exception ex) {
        logDebug("queryPowerStateOnce: failed (${ex.message})")
        return null
    }
}

void on() {
    String power = getPowerState()
    if (power == 'on') {
        logDebug('on: already on')
        sendEvent(name: 'switch', value: 'on')
    } else if (power == 'off') {
        logDebug('on: reachable but off, sending KEY_POWER')
        sendKeyInternal(KEY_POWER)
        sendEvent(name: 'switch', value: 'on')
    } else {
        logDebug('on: unreachable, sending WOL')
        if (sendWol()) {
            sendEvent(name: 'switch', value: 'on')
        }
    }
}

void off() {
    String power = getPowerState()
    if (power == 'off' || power == 'unreachable') {
        logDebug('off: already off or unreachable')
    } else {
        sendKeyInternal(KEY_POWER)
    }
    sendEvent(name: 'switch', value: 'off')
}

void sendKeyInternal(String key) {
    state.remove('pendingKeys')
    Map data = [
        method: 'ms.remote.control',
        params: [
            Cmd: 'Click',
            DataOfCmd: key,
            Option: false,
            TypeOfRemote: 'SendRemoteKey',
        ],
    ]
    sendMessage(JsonOutput.toJson(data))
}

void connect() {
    String name = 'Hubitat Tizen TV'.bytes.encodeBase64().toString()
    String url = "wss://${deviceIp}:8002/api/v2/channels/samsung.remote.control?name=${name}"
    String token = tvWsToken ?: state.token
    if (token) {
        url += "&token=${token}"
    }
    interfaces.webSocket.connect([ignoreSSLIssues: true], url)
}

void sendMessage(String data) {
    state.pendingMessage = data
    state.remove('expectedClose')
    connect()
}

void inputHdmi1() {
    sendKeySequence([KEY_SOURCE, KEY_DOWN, KEY_ENTER])
}

void sendKeySequence(List<String> keys) {
    sendKeyInternal(keys[0])
    state.pendingKeys = keys.drop(1)
}

void sendNextQueuedKey() {
    List pending = state.pendingKeys
    if (pending) {
        String next = pending[0]
        List remaining = pending.drop(1)
        sendKeyInternal(next)
        state.pendingKeys = remaining
    }
}

void sendKey(String key) {
    sendKeyInternal(key)
}

void parse(String message) {
    Map resp = new JsonSlurper().parseText(message)
    logDebug("parse: event=${resp.event}")
    if (resp.event == 'ms.channel.connect') {
        String newToken = resp.data?.token
        if (newToken && newToken != state.token) {
            state.token = newToken
            device.updateSetting('tvWsToken', [type: 'text', value: newToken])
            log.info('parse: pairing token updated')
        }
        if (state.pendingMessage) {
            interfaces.webSocket.sendMessage(state.pendingMessage)
            state.remove('pendingMessage')
            state.expectedClose = true
            interfaces.webSocket.close()
            if (state.pendingKeys) {
                runIn(1, 'sendNextQueuedKey')
            }
        }
    }
}

void webSocketStatus(String message) {
    logDebug("webSocketStatus: ${message}")
    if (message == 'status: open') {
        return
    }
    if (state.expectedClose) {
        return
    }
    log.warn("webSocketStatus: ${message}")
    interfaces.webSocket.close()
}

void logDebug(String msg) {
    if (logEnable) { log.debug(msg) }
}
