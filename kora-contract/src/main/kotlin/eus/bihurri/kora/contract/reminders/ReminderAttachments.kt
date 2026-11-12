package eus.bihurri.kora.contract.reminders

import eus.bihurri.kora.contract.CapabilityId
import eus.bihurri.kora.contract.files.AttachmentsCapability

/**
 * The files attached to a reminder: the shared attachments definition ([AttachmentsCapability]) for
 * [RemindersCapability], where `itemId` is a `reminderId`.
 *
 * The attachments of a reminder are listed in [ReminderDetail.attachments]. A provider of this capability also
 * provides `reminders` and `files`.
 *
 * Version history:
 * - 1.0: `add`, `remove`, `open`.
 */
public object ReminderAttachments : AttachmentsCapability(
    id = CapabilityId("reminders.attachments"),
    item = "reminder",
    itemIdName = "reminderId",
)
