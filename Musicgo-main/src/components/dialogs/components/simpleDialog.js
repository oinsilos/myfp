import React from "react";
import { hideDialog } from "../useDialog";
import Dialog from "./base";
import { useI18N } from "@/core/i18n";
export default function SimpleDialog(props) {
    const { title, content, onOk, okText, cancelText } = props;
    const { t } = useI18N();
    const actions = onOk
        ? [
            {
                title: cancelText ?? t("common.cancel"),
                type: "normal",
                onPress: hideDialog,
            },
            {
                title: okText ?? t("common.confirm"),
                type: "primary",
                onPress() {
                    onOk?.();
                    hideDialog();
                },
            },
        ]
        : [
            {
                title: okText ?? t("dialog.errorLogKnow"),
                type: "primary",
                onPress() {
                    hideDialog();
                },
            },
        ];
    return (<Dialog onDismiss={hideDialog}>
            <Dialog.Title withDivider>{title}</Dialog.Title>
            <Dialog.Content needScroll>{content}</Dialog.Content>
            <Dialog.Actions actions={actions}/>
        </Dialog>);
}
