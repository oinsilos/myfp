export function safeStringify(raw) {
    try {
        return JSON.stringify(raw);
    }
    catch {
        return "";
    }
}
export function safeParse(raw) {
    try {
        if (!raw) {
            return null;
        }
        return JSON.parse(raw);
    }
    catch {
        return null;
    }
}
