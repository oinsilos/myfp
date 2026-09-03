/**
 * 全局持久化的状态
 */
import getOrCreateMMKV from "@/utils/getOrCreateMMKV";
import { useEffect, useState } from "react";
import { safeParse } from "./jsonUtil";
// Internal Method
const getStore = () => {
    return getOrCreateMMKV("App.PersistStatus");
};
function set(key, value) {
    const store = getStore();
    if (value === undefined) {
        store.delete(key);
    }
    else {
        store.set(key, JSON.stringify(value));
    }
}
function get(key) {
    const store = getStore();
    const raw = store.getString(key);
    if (raw) {
        return safeParse(raw);
    }
    return null;
}
function useValue(key, defaultValue) {
    const [state, setState] = useState(get(key) ?? defaultValue ?? null);
    useEffect(() => {
        const store = getStore();
        const sub = store.addOnValueChangedListener(changedKey => {
            if (key === changedKey) {
                setState(get(key));
            }
        });
        return () => {
            sub.remove();
        };
    }, []);
    return state;
}
const PersistStatus = {
    get,
    set,
    useValue,
};
export default PersistStatus;
