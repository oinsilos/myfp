import { useEffect, useState } from "react";
export default class StateMapper {
    getFun;
    cbs = new Set([]);
    constructor(getFun) {
        this.getFun = getFun;
    }
    notify = () => {
        this.cbs.forEach(_ => _?.());
    };
    useMappedState = () => {
        const [_state, _setState] = useState(this.getFun);
        const updateState = () => {
            _setState(this.getFun());
        };
        useEffect(() => {
            this.cbs.add(updateState);
            return () => {
                this.cbs.delete(updateState);
            };
        }, []);
        return _state;
    };
}
export class GlobalState {
    value;
    stateMapper;
    constructor(initValue) {
        this.value = initValue;
        this.stateMapper = new StateMapper(this.getValue);
    }
    getValue = () => {
        return this.value;
    };
    useValue = () => {
        return this.stateMapper.useMappedState();
    };
    setValue = (value) => {
        let newValue;
        if (typeof value === "function") {
            newValue = value(this.value);
        }
        else {
            newValue = value;
        }
        this.value = newValue;
        this.stateMapper.notify();
    };
}
