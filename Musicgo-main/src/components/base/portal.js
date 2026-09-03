import React, { useEffect, useRef } from "react";
import { StyleSheet, View } from "react-native";
import { atom, useAtomValue, useSetAtom } from "jotai";
const portalsAtom = atom([]);
export default function Portal(props) {
    const { children } = props;
    const keyRef = useRef(null);
    const setPortalsAtoms = useSetAtom(portalsAtom);
    useEffect(() => {
        if (!keyRef.current) {
            // mount
            keyRef.current = Math.random().toString().slice(2);
            // console.log("MOUNT!", keyRef.current);
            setPortalsAtoms(portals => [
                ...portals,
                { key: keyRef.current, children },
            ]);
        }
        else {
            // update
            // console.log("UPDATE!", keyRef.current);
            setPortalsAtoms(portals => portals.map(it => it.key === keyRef.current ? { ...it, children } : it));
        }
    }, [children]);
    useEffect(() => {
        return () => {
            if (keyRef.current) {
                // console.log("UNMOUNT!", keyRef.current);
                setPortalsAtoms(portals => portals.filter(it => it.key !== keyRef.current));
            }
        };
    }, []);
    return null;
}
const styles = StyleSheet.create({
    portalContainer: {
        zIndex: 20000,
    },
});
const composedStyle = [StyleSheet.absoluteFill, styles.portalContainer];
export function PortalHost() {
    const portals = useAtomValue(portalsAtom);
    return (<>
            {portals.map(({ key, children }) => (<View key={key} collapsable={false} pointerEvents="box-none" style={composedStyle}>
                    {children}
                </View>))}
        </>);
}
