import { useEffect, useRef } from "react";
export default function useOnceEffect(cb, deps) {
    const flag = useRef(false);
    useEffect(() => {
        let result;
        if (flag.current) {
            return result;
        }
        if (!deps || deps.every(_ => !!_)) {
            flag.current = true;
            result = cb();
        }
        return result;
    }, deps);
}
