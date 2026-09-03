import { useEffect, useRef } from "react";
export default function (msg, deps = []) {
    const idRef = useRef();
    useEffect(() => {
        idRef.current = Math.random();
        console.log("Mount", msg ?? "", idRef.current);
        return () => {
            console.log("Unmount", msg ?? "", idRef.current);
        };
    }, []);
    useEffect(() => {
        if (deps?.length !== 0) {
            console.log("State Change", msg ?? "", idRef.current);
        }
    }, deps);
    useEffect(() => {
        idRef.current && console.log("Rerender: ", msg ?? "", idRef.current);
    });
}
