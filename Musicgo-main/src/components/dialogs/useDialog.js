import { GlobalState } from "@/utils/stateMapper";
import { useCallback } from "react";
export const dialogInfoStore = new GlobalState({
    name: null,
    payload: null,
});
export function showDialog(name, payload) {
    dialogInfoStore.setValue({
        name,
        payload,
    });
}
export function hideDialog() {
    dialogInfoStore.setValue({
        name: null,
        payload: null,
    });
}
export default function useDialog() {
    const showDialog = useCallback((name, payload) => {
        dialogInfoStore.setValue({
            name,
            payload,
        });
    }, []);
    const hideDialog = useCallback(() => {
        dialogInfoStore.setValue({
            name: null,
            payload: null,
        });
    }, []);
    return { showDialog, hideDialog };
}
export function getCurrentDialog() {
    return dialogInfoStore.getValue();
}
