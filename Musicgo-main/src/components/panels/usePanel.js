import { GlobalState } from "@/utils/stateMapper";
import { DeviceEventEmitter } from "react-native";
/** 浮层信息 */
export const panelInfoStore = new GlobalState({
    name: null,
    payload: null,
});
export function showPanel(name, payload) {
    if (panelInfoStore.getValue().name) {
        DeviceEventEmitter.emit("hidePanel", () => {
            panelInfoStore.setValue({
                name,
                payload,
            });
        });
    }
    else {
        panelInfoStore.setValue({
            name,
            payload,
        });
    }
}
export function hidePanel() {
    DeviceEventEmitter.emit("hidePanel");
}
