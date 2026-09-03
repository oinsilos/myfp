import { showToast } from "@/components/base/toast";
function success(message, config) {
    showToast({
        message,
        ...config,
        type: "success",
    });
}
function warn(message, config) {
    showToast({
        message,
        ...config,
        type: "warn",
    });
}
const Toast = {
    success,
    warn,
};
export default Toast;
