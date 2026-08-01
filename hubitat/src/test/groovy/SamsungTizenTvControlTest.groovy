import me.biocomp.hubitat_ci.api.common_api.DeviceWrapper
import me.biocomp.hubitat_ci.api.common_api.HubAction
import me.biocomp.hubitat_ci.api.common_api.InterfaceHelper
import me.biocomp.hubitat_ci.api.common_api.Log
import me.biocomp.hubitat_ci.api.common_api.WebSocket
import me.biocomp.hubitat_ci.api.device_api.DeviceExecutor
import me.biocomp.hubitat_ci.api.Protocol
import me.biocomp.hubitat_ci.device.HubitatDeviceSandbox
import groovy.json.JsonSlurper
import spock.lang.Specification

class SamsungTizenTvControlTest extends Specification {
    HubitatDeviceSandbox sandbox = new HubitatDeviceSandbox(new File('SamsungTizenTvControl.groovy'))

    def "driver definition and preferences are well-formed"() {
        given:
            def script = sandbox.run()

        expect:
            script.producedDefinition.options.name == 'Samsung Tizen TV Control'
            script.producedDefinition.options.namespace == 'hugoh'
            script.producedDefinition.options.author == 'Hugo Haas'
            script.producedDefinition.capabilities.containsAll(['Switch', 'Actuator', 'Refresh'])
    }

    def "refresh sets switch to on when TV reports PowerState on"() {
        given:
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { Map opts, Closure handler ->
                    handler([data: [device: [PowerState: 'on']]])
                }
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.refresh()

        then:
            1 * api.sendEvent([name: 'switch', value: 'on'])
    }

    def "refresh sets switch to off when TV reports empty PowerState"() {
        given:
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { Map opts, Closure handler ->
                    handler([data: [device: [PowerState: '']]])
                }
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.refresh()

        then:
            1 * api.sendEvent([name: 'switch', value: 'off'])
    }

    def "refresh sets switch to off when TV is unreachable"() {
        given:
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { throw new SocketTimeoutException('timed out') }
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.refresh()

        then:
            1 * api.sendEvent([name: 'switch', value: 'off'])
    }

    def "on sends KEY_POWER over websocket when TV is reachable but off"() {
        given:
            String sentJson = null
            WebSocket ws = Mock(WebSocket)
            InterfaceHelper interfaces = Mock(InterfaceHelper) {
                _ * getWebSocket() >> ws
            }
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { Map opts, Closure handler ->
                    handler([data: [device: [PowerState: '']]])
                }
                _ * getInterfaces() >> interfaces
                _ * getState() >> [:]
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.on()
            script.parse('{"event":"ms.channel.connect","data":{}}')

        then:
            1 * ws.connect(_, _)
            1 * ws.sendMessage(_) >> { String msg -> sentJson = msg }
            1 * ws.close()

        and:
            new JsonSlurper().parseText(sentJson).params.DataOfCmd == 'KEY_POWER'
    }

    def "on is a no-op when TV already reports on"() {
        given:
            InterfaceHelper interfaces = Mock(InterfaceHelper)
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { Map opts, Closure handler ->
                    handler([data: [device: [PowerState: 'on']]])
                }
                _ * getInterfaces() >> interfaces
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.on()

        then:
            0 * interfaces.webSocket
            1 * api.sendEvent([name: 'switch', value: 'on'])
    }

    def "off sends KEY_POWER over websocket when TV is on"() {
        given:
            String sentJson = null
            WebSocket ws = Mock(WebSocket)
            InterfaceHelper interfaces = Mock(InterfaceHelper) {
                _ * getWebSocket() >> ws
            }
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { Map opts, Closure handler ->
                    handler([data: [device: [PowerState: 'on']]])
                }
                _ * getInterfaces() >> interfaces
                _ * getState() >> [:]
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.off()
            script.parse('{"event":"ms.channel.connect","data":{}}')

        then:
            1 * ws.sendMessage(_) >> { String msg -> sentJson = msg }
            1 * ws.close()

        and:
            new JsonSlurper().parseText(sentJson).params.DataOfCmd == 'KEY_POWER'
    }

    def "off is a no-op when TV already reports off"() {
        given:
            InterfaceHelper interfaces = Mock(InterfaceHelper)
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { Map opts, Closure handler ->
                    handler([data: [device: [PowerState: '']]])
                }
                _ * getInterfaces() >> interfaces
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.off()

        then:
            0 * interfaces.webSocket
            1 * api.sendEvent([name: 'switch', value: 'off'])
    }

    def "updated fetches and stores the TV MAC as deviceNetworkId"() {
        given:
            DeviceWrapper device = Mock(DeviceWrapper)
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { Map opts, Closure handler ->
                    handler([data: [device: [wifiMac: 'B0:F2:F6:5C:9D:7B']]])
                }
                _ * getDevice() >> device
                _ * getLog() >> Mock(Log)
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.updated()

        then:
            1 * device.setDeviceNetworkId('B0F2F65C9D7B')
    }

    def "updated does not re-set deviceNetworkId when the MAC is unchanged"() {
        given:
            DeviceWrapper device = Mock(DeviceWrapper) {
                _ * getDeviceNetworkId() >> 'B0F2F65C9D7B'
            }
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { Map opts, Closure handler ->
                    handler([data: [device: [wifiMac: 'B0:F2:F6:5C:9D:7B']]])
                }
                _ * getDevice() >> device
                _ * getLog() >> Mock(Log)
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.updated()

        then:
            0 * device.setDeviceNetworkId(_)
    }

    def "on sends WOL to the stored MAC when TV is unreachable"() {
        given:
            DeviceWrapper device = Mock(DeviceWrapper) {
                _ * getDeviceNetworkId() >> 'B0F2F65C9D7B'
            }
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { throw new SocketTimeoutException('timed out') }
                _ * getDevice() >> device
            }
            def script = sandbox.run(
                api: api,
                userSettingValues: [deviceIp: '192.168.68.240', wolMac: '']
            )

        when:
            script.on()

        then:
            1 * api.sendHubCommand({ HubAction action ->
                action.action == 'wake on lan B0F2F65C9D7B' && action.protocol == Protocol.LAN
            })
            1 * api.sendEvent([name: 'switch', value: 'on'])
    }

    def "on does not report switch on when TV is unreachable and no MAC is known"() {
        given:
            DeviceWrapper device = Mock(DeviceWrapper) {
                _ * getDeviceNetworkId() >> null
            }
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { throw new SocketTimeoutException('timed out') }
                _ * getDevice() >> device
                _ * getLog() >> Mock(Log)
            }
            def script = sandbox.run(
                api: api,
                userSettingValues: [deviceIp: '192.168.68.240', wolMac: '']
            )

        when:
            script.on()

        then:
            0 * api.sendHubCommand(_)
            0 * api.sendEvent(_)
    }

    def "sendWol uses the wolMac preference override when set"() {
        given:
            DeviceWrapper device = Mock(DeviceWrapper) {
                _ * getDeviceNetworkId() >> 'B0F2F65C9D7B'
            }
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * getDevice() >> device
            }
            def script = sandbox.run(
                api: api,
                userSettingValues: [deviceIp: '192.168.68.240', wolMac: 'AABBCCDDEEFF']
            )

        when:
            script.sendWol()

        then:
            1 * api.sendHubCommand({ HubAction action ->
                action.action == 'wake on lan AABBCCDDEEFF' && action.protocol == Protocol.LAN
            })
    }

    def "inputHdmi1 sends KEY_SOURCE first and schedules the next queued key"() {
        given:
            String sentJson = null
            WebSocket ws = Mock(WebSocket)
            InterfaceHelper interfaces = Mock(InterfaceHelper) {
                _ * getWebSocket() >> ws
            }
            Map stateMap = [:]
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * getInterfaces() >> interfaces
                _ * getState() >> stateMap
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.inputHdmi1()
            script.parse('{"event":"ms.channel.connect","data":{}}')

        then:
            1 * ws.sendMessage(_) >> { String msg -> sentJson = msg }
            1 * ws.close()
            1 * api.runIn(_, 'sendNextQueuedKey')

        and:
            new JsonSlurper().parseText(sentJson).params.DataOfCmd == 'KEY_SOURCE'
            stateMap.pendingKeys == ['KEY_DOWN', 'KEY_ENTER']
    }

    def "sendNextQueuedKey sends KEY_DOWN and schedules KEY_ENTER next"() {
        given:
            String sentJson = null
            WebSocket ws = Mock(WebSocket)
            InterfaceHelper interfaces = Mock(InterfaceHelper) {
                _ * getWebSocket() >> ws
            }
            Map stateMap = [pendingKeys: ['KEY_DOWN', 'KEY_ENTER']]
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * getInterfaces() >> interfaces
                _ * getState() >> stateMap
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.sendNextQueuedKey()
            script.parse('{"event":"ms.channel.connect","data":{}}')

        then:
            1 * ws.sendMessage(_) >> { String msg -> sentJson = msg }
            1 * ws.close()
            1 * api.runIn(_, 'sendNextQueuedKey')

        and:
            new JsonSlurper().parseText(sentJson).params.DataOfCmd == 'KEY_DOWN'
            stateMap.pendingKeys == ['KEY_ENTER']
    }

    def "sendNextQueuedKey sends KEY_ENTER as the final key with no further scheduling"() {
        given:
            String sentJson = null
            WebSocket ws = Mock(WebSocket)
            InterfaceHelper interfaces = Mock(InterfaceHelper) {
                _ * getWebSocket() >> ws
            }
            Map stateMap = [pendingKeys: ['KEY_ENTER']]
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * getInterfaces() >> interfaces
                _ * getState() >> stateMap
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.sendNextQueuedKey()
            script.parse('{"event":"ms.channel.connect","data":{}}')

        then:
            1 * ws.sendMessage(_) >> { String msg -> sentJson = msg }
            1 * ws.close()
            0 * api.runIn(_, _)

        and:
            new JsonSlurper().parseText(sentJson).params.DataOfCmd == 'KEY_ENTER'
            stateMap.pendingKeys == []
    }

    def "sendKey passes its argument through raw"() {
        given:
            String sentJson = null
            WebSocket ws = Mock(WebSocket)
            InterfaceHelper interfaces = Mock(InterfaceHelper) {
                _ * getWebSocket() >> ws
            }
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * getInterfaces() >> interfaces
                _ * getState() >> [:]
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.sendKey('KEY_MENU')
            script.parse('{"event":"ms.channel.connect","data":{}}')

        then:
            1 * ws.sendMessage(_) >> { String msg -> sentJson = msg }
            1 * ws.close()

        and:
            new JsonSlurper().parseText(sentJson).params.DataOfCmd == 'KEY_MENU'
    }

    def "sendKey clears a stale pendingKeys queue left over from an interrupted inputHdmi1 sequence"() {
        given:
            String sentJson = null
            WebSocket ws = Mock(WebSocket)
            InterfaceHelper interfaces = Mock(InterfaceHelper) {
                _ * getWebSocket() >> ws
            }
            Map stateMap = [pendingKeys: ['KEY_DOWN', 'KEY_ENTER']]
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * getInterfaces() >> interfaces
                _ * getState() >> stateMap
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.sendKey('KEY_MENU')
            script.parse('{"event":"ms.channel.connect","data":{}}')

        then:
            1 * ws.sendMessage(_) >> { String msg -> sentJson = msg }
            1 * ws.close()
            0 * api.runIn(_, _)

        and:
            new JsonSlurper().parseText(sentJson).params.DataOfCmd == 'KEY_MENU'
            !stateMap.pendingKeys
    }

    def "parse captures and stores the pairing token from ms.channel.connect"() {
        given:
            DeviceWrapper device = Mock(DeviceWrapper)
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * getDevice() >> device
                _ * getState() >> [:]
                _ * getLog() >> Mock(Log)
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])
            String message = '{"event":"ms.channel.connect","data":{"token":"12689500"}}'

        when:
            script.parse(message)

        then:
            1 * device.updateSetting('tvWsToken', [type: 'text', value: '12689500'])
            script.state.token == '12689500'
    }

    def "parse ignores non-connect events"() {
        given:
            DeviceWrapper device = Mock(DeviceWrapper)
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * getDevice() >> device
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])
            String message = '{"event":"ms.channel.ready"}'

        when:
            script.parse(message)

        then:
            0 * device.updateSetting(_, _)
    }

    def "webSocketStatus warns and closes the socket on a genuine, unexpected non-open status"() {
        given:
            WebSocket ws = Mock(WebSocket)
            InterfaceHelper interfaces = Mock(InterfaceHelper) {
                _ * getWebSocket() >> ws
            }
            Log log = Mock(Log)
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * getInterfaces() >> interfaces
                _ * getLog() >> log
                _ * getState() >> [:]
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.webSocketStatus('status: closing')

        then:
            1 * log.warn(_)
            1 * ws.close()
    }

    def "webSocketStatus does not warn or close again after an intentional close"() {
        given:
            WebSocket ws = Mock(WebSocket)
            InterfaceHelper interfaces = Mock(InterfaceHelper) {
                _ * getWebSocket() >> ws
            }
            Log log = Mock(Log)
            Map stateMap = [expectedClose: true]
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * getInterfaces() >> interfaces
                _ * getLog() >> log
                _ * getState() >> stateMap
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.webSocketStatus('status: closing')

        then:
            0 * log.warn(_)
            0 * ws.close()
            stateMap.expectedClose == true
    }

    def "webSocketStatus stays silent across multiple non-open callbacks after one intentional close"() {
        given:
            WebSocket ws = Mock(WebSocket)
            InterfaceHelper interfaces = Mock(InterfaceHelper) {
                _ * getWebSocket() >> ws
            }
            Log log = Mock(Log)
            Map stateMap = [expectedClose: true]
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * getInterfaces() >> interfaces
                _ * getLog() >> log
                _ * getState() >> stateMap
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            script.webSocketStatus('status: closing')
            script.webSocketStatus('failure: connection reset')

        then:
            0 * log.warn(_)
    }

    def "getPowerState retries once after a transient failure and succeeds"() {
        given:
            int attempts = 0
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { Map opts, Closure handler ->
                    attempts++
                    if (attempts == 1) {
                        throw new SocketTimeoutException('timed out')
                    }
                    handler([data: [device: [PowerState: 'on']]])
                }
                1 * pauseExecution(1000)
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            String result = script.powerState

        then:
            result == 'on'
            attempts == 2
    }

    def "getPowerState returns unreachable if both attempts fail"() {
        given:
            DeviceExecutor api = Mock(DeviceExecutor) {
                _ * httpGet(_, _) >> { throw new SocketTimeoutException('timed out') }
                1 * pauseExecution(1000)
            }
            def script = sandbox.run(api: api, userSettingValues: [deviceIp: '192.168.68.240'])

        when:
            String result = script.powerState

        then:
            result == 'unreachable'
    }
}
