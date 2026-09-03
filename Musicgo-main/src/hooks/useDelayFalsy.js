import { useRef, useState } from "react";
export default function useDelayFalsy(init, ms = 0) {
    const [_state, _setState] = useState(init);
    const timer = useRef();
    function setState(st) {
        if (st === undefined || st === null || st === false) {
            timer.current && clearTimeout(timer.current);
            timer.current = setTimeout(() => {
                _setState(st);
                timer.current = undefined;
            }, ms);
            return;
        }
        timer.current && clearTimeout(timer.current);
        timer.current = undefined;
        _setState(st);
    }
    return [_state, setState, _setState];
}
