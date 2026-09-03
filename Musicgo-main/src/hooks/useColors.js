import { useTheme } from "@react-navigation/native";
import Color from "color";
import { useMemo } from "react";
export default function useColors() {
    const { colors } = useTheme();
    const cColors = useMemo(() => {
        return {
            ...colors,
            textSecondary: Color(colors.text).alpha(0.7).toString(),
            // @ts-ignore
            background: colors.pageBackground ?? colors.background,
        };
    }, [colors]);
    return cColors;
}
