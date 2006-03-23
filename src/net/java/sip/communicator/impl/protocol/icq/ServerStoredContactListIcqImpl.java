/*
 * SIP Communicator, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.icq;

import net.java.sip.communicator.service.protocol.*;
import net.kano.joustsim.oscar.oscar.service.ssi.*;
import java.util.*;
import net.java.sip.communicator.util.*;
import net.kano.joustsim.Screenname;
import net.kano.joscar.snaccmd.ssi.SsiItem;
import net.java.sip.communicator.service.protocol.event.*;
import net.kano.joscar.ssiitem.*;
import net.kano.joustsim.oscar.*;

/**
 * This class encapsulates the net.kano BuddyList class. Once created, it will
 * register itself as a listener to the encapsulated BuddyList and modify it's
 * local copy of Contacts and ContactGroups every time an event is generated
 * by the underlying joustsim framework. The class would also generate
 * corresponding sip-communicator events to all events coming from joustsim.
 *
 * @author Emil Ivov
 */
public class ServerStoredContactListIcqImpl
        implements BuddyInfoTrackerListener
{
    private static final Logger logger =
        Logger.getLogger(ServerStoredContactListIcqImpl.class);
    /**
     * The joustsim buddy list that we encapsulate
     */
    private MutableBuddyList buddyList = null;

    /**
     * Our joustsim buddy list event listener
     */
    private BuddyListListener buddyListListener = new BuddyListListener();

    /**
     * Our joustsim group change listener.
     */
    private GroupChangeListener jsimGroupChangeListener
        = new GroupChangeListener();

    /**
     * A joust sim item change listener.
     */
    private JoustSimItemChangeListener jsimItemChangeListener
        = new JoustSimItemChangeListener();

    /**
     * Our joustsim buddy change listener.
     */
    private JoustSimBuddyListener jsimBuddyListener
        = new JoustSimBuddyListener();

    /**
     * The root contagroup. The container for all ICQ buddies and groups.
     */
    private RootContactGroupIcqImpl rootGroup;

    /**
     * Listeners that others registered with us for contact list events.
     */
    private Vector contactListListeners = new Vector();

    /**
     * The joust sim service that deals with server stored information.
     */
    private SsiService jSimSsiService = null;

    /**
     * The operation set that created us and that we could use when dispatching
     * subscription events.
     */
    private OperationSetPersistentPresenceIcqImpl parentOperationSet = null;

    /**
     * The icqProvider that is on top of us.
     */
    private ProtocolProviderServiceIcqImpl icqProvider = null;

    /**
     * Listeners that would receive event notifications for changes in group
     * names or other properties, removal or creation of groups.
     */
    private Vector serverStoredGroupListeners = new Vector();

    /**
     * Creates a ServerStoredContactList wrapper for the specified BuddyList.
     */
    ServerStoredContactListIcqImpl()
    {
        //don't add the sub ICQ groups to rootGroup here as we'll be having
        //event notifications for every one of them through the
        //RetroactiveBuddyListListener
    }

    /**
     * Returns the root group of the contact list.
     *
     * @return the root ContactGroup for the ContactList
     */
    public ContactGroup getRootGroup()
    {
        return rootGroup;
    }

    /**
     * Registers the specified group listener so that it would receive events
     * on group modification/creation/destruction.
     * @param l the ServerStoredGroupListener to register for group events
     */
    void addGroupListener(ServerStoredGroupListener l)
    {
        synchronized(serverStoredGroupListeners){
            this.serverStoredGroupListeners.add(l);
        }
    }

    /**
     * Removes the specified group listener so that it won't receive further
     * events on group modification/creation/destruction.
     * @param l the ServerStoredGroupListener to unregister
     */
    void removeGroupListener(ServerStoredGroupListener l)
    {
        synchronized(serverStoredGroupListeners){
            this.serverStoredGroupListeners.remove(l);
        }
    }

    /**
     * Creates the corresponding event and notifies all
     * <tt>ServerStoredGroupListener</tt>s that the source group has been
     * removed, changed, renamed or whatever happened to it.
     * @param group the ContactGroup that has been created/modified/removed
     * @param eventID the id of the event to generate.
     */
    private void fireGroupEvent(ContactGroupIcqImpl group, int eventID)
    {
        ServerStoredGroupEvent evt = new ServerStoredGroupEvent(
                  group
                , eventID
                , parentOperationSet.getServerStoredContactListRoot()
                , icqProvider
                , parentOperationSet);

        logger.trace("Will dispatch the following grp event: " + evt);

        synchronized (serverStoredGroupListeners){
            Iterator listeners = this.serverStoredGroupListeners.iterator();

            while (listeners.hasNext())
            {
                ServerStoredGroupListener l
                    = (ServerStoredGroupListener) listeners.next();
                if (eventID == ServerStoredGroupEvent.GROUP_REMOVED_EVENT)
                    l.groupRemoved(evt);
                else if (eventID == ServerStoredGroupEvent.GROUP_RENAMED_EVENT)
                    l.groupNameChanged(evt);
                else if (eventID == ServerStoredGroupEvent.GROUP_CREATED_EVENT)
                    l.groupCreated(evt);
            }
        }
    }

    private void fireGroupsReordered()
    {
        /** @todo implement fireGroupsReordered *///no need of args since it
        //could only mean one thing
    }

    /**
     * Make the parent persistent presence operation set dispatch a contact
     * added event.
     * @param parentGroup the group where the new contact was added
     * @param contact the contact that was added
     * @param index the index at which it was added.
     */
    private void fireContactAdded( ContactGroupIcqImpl parentGroup,
                                   ContactIcqImpl contact,
                                   int index)
    {
        //bail out if no one's listening
        if(parentOperationSet == null){
            logger.debug("No presence op. set available. Bailing out.");
            return;
        }

        //dispatch
        parentOperationSet.fireSubscriptionEvent(
            SubscriptionEvent.SUBSCRIPTION_CREATED, contact, parentGroup);
    }

    /**
     * Make the parent persistent presence operation set dispatch a contact
     * removed event.
     * @param parentGroup the group where that the removed contact belonged to.
     * @param contact the contact that was removed.
     */
    private void fireContactRemoved( ContactGroupIcqImpl parentGroup,
                                     ContactIcqImpl contact)
    {
        //bail out if no one's listening
        if(parentOperationSet == null){
            logger.debug("No presence op. set available. Bailing out.");
            return;
        }

        //dispatch
        parentOperationSet.fireSubscriptionEvent(
            SubscriptionEvent.SUBSCRIPTION_CREATED, contact, parentGroup);
    }

    private void fireContactsReordered( ContactGroupIcqImpl parentGroup)
    {
        /** @todo implement fireContactsReordered() */
    }

    /**
     * Retrns a reference to the provider that created us.
     * @return a reference to a ProtocolProviderServiceIcqImpl instance.
     */
    ProtocolProviderServiceIcqImpl getParentProvider()
    {
        return icqProvider;
    }

    /**
     * Returns the index of the ContactGroup containing the specified joust sim
     * group.
     * @param joustSimGroup the joust sim group we're looking for.
     * @return the index of the ContactGroup containing the specified
     * joustSimGroup or -1 if no containing ContactGroup exists.
     */
    public int findContactGroupIndex(Group joustSimGroup)
    {
        Iterator contactGroups = rootGroup.subgroups();
        int index = 0;

        for (; contactGroups.hasNext(); index++)
        {
            ContactGroupIcqImpl contactGroup
                = (ContactGroupIcqImpl) contactGroups.next();

            if (joustSimGroup == contactGroup.getJoustSimSourceGroup())
                return index;

        }
        return -1;
    }

    /**
     * Returns the ContactGroup corresponding to the specified joust sim group.
     * @param joustSimGroup the joust sim group we're looking for.
     * @return the ContactGroup corresponding to the specified joustSimGroup
     * null if no containing ContactGroup exists.
     */
    public ContactGroupIcqImpl findContactGroup(Group joustSimGroup)
    {
        Iterator contactGroups = rootGroup.subgroups();

        while(contactGroups.hasNext())
        {
            ContactGroupIcqImpl contactGroup
                = (ContactGroupIcqImpl) contactGroups.next();

            if (joustSimGroup == contactGroup.getJoustSimSourceGroup())
                return contactGroup;

        }
        return null;
    }

    /**
     * Returns the Contact with the specified screenname (or icq UIN) or null if
     * no such screenname was found.
     *
     * @param screenName the screen name (or ICQ UIN) of the contact to find.
     * @return the <tt>Contact</tt> carrying the specified
     * <tt>screenName</tt> or <tt>null</tt> if no such contact exits.
     */
    public ContactIcqImpl findContactByScreenName(String screenName)
    {
        Iterator contactGroups = rootGroup.subgroups();
        ContactIcqImpl result = null;

        while(contactGroups.hasNext())
        {
            ContactGroupIcqImpl contactGroup
                = (ContactGroupIcqImpl) contactGroups.next();

            result = contactGroup.findContact(screenName);

            if (result != null)
                return result;

        }
        return null;
    }


    /**
     * Returns the ContactGroup containing the specified contact or null
     * if no such group or contact exist.
     *
     * @param child the contact whose parent group we're looking for.
     * @return the <tt>ContactGroup</tt> containing the specified
     * <tt>contact</tt> or <tt>null</tt> if no such groupo or contact
     * exist.
     */
    public ContactGroupIcqImpl findContactGroup(ContactIcqImpl child)
    {
        Iterator contactGroups = rootGroup.subgroups();

        while(contactGroups.hasNext())
        {
            ContactGroupIcqImpl contactGroup
                = (ContactGroupIcqImpl) contactGroups.next();

            if( contactGroup.findContact(child.getJoustSimBuddy())!= null)
                return contactGroup;
        }
        return null;
    }

    /**
     * Adds a new contact with the specified screenname to the list under a
     * default location.
     * @param screenname the screenname or icq uin of the contact to add.
     */
    public void addContact(String screenname)
    {
        ContactGroupIcqImpl parent =
            (ContactGroupIcqImpl)getRootGroup().getGroup(0);

        addContact(parent, screenname);
    }

    /**
     * Adds a new contact with the specified screenname to the list under the
     * specified group.
     * @param screenname the screenname or icq uin of the contact to add.
     * @param parent the group under which we want the new contact placed.
     */
    public void addContact(ContactGroupIcqImpl parent, String screenname)
    {
        logger.trace("Addint contact " + screenname
                     + " to parent=" + parent.getGroupName());

        //if the contact is already in the contact list, only broadcast an event
        final ContactIcqImpl existingContact
            = findContactByScreenName(screenname);

        //if the contact already exists - just issue an event.
        if( existingContact != null)
        {
            logger.debug("Contact " + screenname + " already exists. Gen. evt.");
            //broadcast the event in a separate thread so that we don't
            //block the calling thread.
            new Thread(){
                public void run(){
                    parentOperationSet.fireSubscriptionEvent(
                        SubscriptionEvent.SUBSCRIPTION_CREATED,
                        existingContact,
                        findContactGroup(existingContact));
                }
            }.start();
            return;
        }

        logger.trace("Adding the contact to the specified group.");
        //extract the top level group
        AddMutableGroup group = parent.getJoustSimSourceGroup();

        group.addBuddy(screenname);
    }

    /**
     * Creates the specified group on the server stored contact list.
     * @param groupName a String containing the name of the new group.
     */
    public void createGroup(String groupName)
    {
        logger.trace("Creating group: " + groupName);
        buddyList.addGroup(groupName);
        logger.trace("Group " +groupName+ " created.");
    }

    /**
     * Removes the specified group from the icq buddy list.
     * @param groupToRemove the group that we'd like removed.
     */
    public void removeGroup(ContactGroupIcqImpl groupToRemove)
    {
        buddyList.deleteGroupAndBuddies(
            groupToRemove.getJoustSimSourceGroup());
    }

    /**
     * Renames the specified group according to the specified new name..
     * @param groupToRename the group that we'd like removed.
     * @param newName the new name of the group
     */
    public void renameGroup(ContactGroupIcqImpl groupToRename, String newName)
    {
        groupToRename.getJoustSimSourceGroup().rename(newName);
    }



    /**
     * Moves the specified <tt>contact</tt> to the group indicated by
     * <tt>newParent</tt>.
     * @param contact the contact that we'd like moved under the new group.
     * @param newParent the group where we'd like the parent placed.
     */
    public void moveContact(ContactIcqImpl contact,
                            ContactGroupIcqImpl newParent)
    {
        List contactsToMove = new ArrayList();
        contactsToMove.add(contact);

        buddyList.moveBuddies(contactsToMove,
                              newParent.getJoustSimSourceGroup());
    }

    /**
     * Sets a reference to the currently active and valid instance of
     * the JoustSIM SsiService that this list is to use for retrieving
     * server stored information
     * @param joustSimSsiService a valid reference to the currently active JoustSIM
     * SsiService.
     * @param parentOperationSet the operation set that created us and that
     * we could use for dispatching subscription events
     * @param icqProvider the icqProvider that has instantiated us.
     */
    void init(  SsiService joustSimSsiService,
                OperationSetPersistentPresenceIcqImpl parentOperationSet,

                ProtocolProviderServiceIcqImpl icqProvider)
    {
        //We need to keep this on top to ensure that the provider
        //and the operationsset would not be null in the incoming events.
        this.parentOperationSet = parentOperationSet;

        this.icqProvider = icqProvider;

        this.rootGroup = new RootContactGroupIcqImpl(icqProvider);

        this.jSimSsiService = joustSimSsiService;
        jSimSsiService.addItemChangeListener(jsimItemChangeListener);

        this.buddyList = jSimSsiService.getBuddyList();
        buddyList.addRetroactiveLayoutListener(buddyListListener);
    }

    private class BuddyListListener
        implements BuddyListLayoutListener
    {
        /**
         * Called by joustsim as a notification of the fact that the server has
         * sent the specified group and that it is actually a member from
         * our contact list. We copy the group locally and generate the
         * corresponding sip-communicator events
         *
         * @param list the BuddyList where this is happening.
         * @param oldItems we don't use it
         * @param newItems we don't use it
         * @param group the new Group that has been added
         * @param buddies the members of the new group.
         */
        public void groupAdded(BuddyList list, List oldItems, List newItems,
                               Group group, List buddies)
        {
            logger.trace("Group added: " + group.getName());
            logger.trace("Buddies: " + buddies);
            ContactGroupIcqImpl newGroup
                = new ContactGroupIcqImpl((MutableGroup)group, buddies,
                        ServerStoredContactListIcqImpl.this);

            //add a joust sim buddy listener to all of the buddies in this group
            for(int i = 0; i < buddies.size(); i++)
                ((Buddy)buddies.get(i)).addBuddyListener(jsimBuddyListener);


            //elements in the newItems list may include groups that have not
            //yet been reported through this method. In order to make sure that
            //we keep the order  specified by the server, we try to add after a
            //newItems member that has a corresponding ContactGroup entry in our
            //contact list, and add the new entry after it
            int groupIndex = newItems.indexOf(group);

            int insertPos = 0;
            if (groupIndex == 0)
            {
                //this is the first group so insert at 0.
                rootGroup.addSubGroup(insertPos, newGroup);
            }
            else
            {
                for (; groupIndex >= 0; groupIndex--)
                {
                    int prevContactGroupIndex
                        = findContactGroupIndex( (Group) newItems.get(groupIndex));

                    //if we've found the nearest previous group that we already
                    //know of we should insert the new group behind it.
                    if (prevContactGroupIndex != -1)
                        insertPos = prevContactGroupIndex + 1;
                }
                rootGroup.addSubGroup(insertPos, newGroup);
            }

            //register a listener for name changes of this group
            group.addGroupListener(jsimGroupChangeListener);

            //tell listeners about the added group
            fireGroupEvent(newGroup, ServerStoredGroupEvent.GROUP_CREATED_EVENT);
        }

        /**
         * Called by joust sim when a group is removed.
         *
         * @param list the <tt>BuddyList</tt> owning the removed group.
         * @param oldItems the list of items as it was before removing the group.
         * @param newItems the list of items as it is after the group is removed.
         * @param group the group that was removed.
         */
        public void groupRemoved(BuddyList list, List oldItems, List newItems,
                                 Group group)
        {
            logger.trace("Group Removed: " + group.getName());
            int index = findContactGroupIndex(group);
            ContactGroupIcqImpl removedGroup
                = (ContactGroupIcqImpl) rootGroup.getGroup(index);

            if (index == -1)
            {
                logger.debug("non existing group: " + group.getName());
                return;
            }

            group.removeGroupListener(jsimGroupChangeListener);

            rootGroup.removeSubGroup(index);

            fireGroupEvent(removedGroup,
                           ServerStoredGroupEvent.GROUP_REMOVED_EVENT);
        }

        /**
         * Called by joust sim to notify us that a new buddy has been added
         * to the contact list.
         *
         * @param list the <tt>BuddyList</tt> owning the newly added buddy.
         * @param joustSimGroup the parent group of the added buddy.
         * @param oldItems unused
         * @param newItems unused
         * @param buddy the newly added <tt>buddy</tt>
         */
        public void buddyAdded(BuddyList list, Group joustSimGroup, List oldItems,
                               List newItems, Buddy buddy)
        {
            ContactIcqImpl newContact = new ContactIcqImpl(
                    buddy, ServerStoredContactListIcqImpl.this);
            ContactGroupIcqImpl parentGroup = findContactGroup(joustSimGroup);

            if (parentGroup == null)
            {
                logger.debug("no parent group "
                             + joustSimGroup + " found for buddy: " + buddy);
                return;
            }

            int buddyIndex = newItems.indexOf(buddy);
            if( buddyIndex == -1 ){
                logger.debug(buddy+" was not present in newItems"+newItems);
            }

            //elements in the newItems list may include buddies that have not
            //yet been reported through this method. In order to make sure that
            //we keep the order  specified by the server, we try to add after a
            //newItems member that has a corresponding ContactGroup entry in our
            //contact list, and add the new entry after it

            int insertPos = 0;
            if (buddyIndex == 0)
            {
                //this is the first group so insert at 0.
                parentGroup.addContact(insertPos, newContact);
            }
            else
            {
                for (; buddyIndex >= 0; buddyIndex--)
                {

                    int prevContactIndex = parentGroup.findContactIndex(
                            (Buddy) newItems.get(buddyIndex));

                    //if we've found the nearest previous group that we already
                    //know of we should insert the new group behind it.
                    if (prevContactIndex != -1)
                        insertPos = prevContactIndex + 1;
                }
                parentGroup.addContact(insertPos, newContact);
            }

            //register a listener for name changes of this buddy
            buddy.addBuddyListener(jsimBuddyListener);

            //tell listeners about the added group
            fireContactAdded(parentGroup, newContact, insertPos);
        }

        /**

b         * Called by joust sim when a buddy is removed
         *
         * @param list the <tt>BuddyList</tt> containing the buddy
         * @param group the joust sim group that the buddy is removed from.
         * @param oldItems unused
         * @param newItems unused
         * @param buddy Buddy
         */
        public void buddyRemoved(BuddyList list, Group group, List oldItems,
                                 List newItems, Buddy buddy)
        {
            ContactGroupIcqImpl parentGroup = findContactGroup(group);
            ContactIcqImpl contactToRemove = parentGroup.findContact(buddy);

            parentGroup.removeContact(contactToRemove);

            buddy.removeBuddyListener(jsimBuddyListener);

            fireContactRemoved(parentGroup, contactToRemove);
        }

        /**
         * Called by joust sim when contacts in a group have been reordered.
         * Removes all Contacts from the concerned group and reinserts them
         * in the right order.
         *
         * @param list the <tt>BuddyList</tt> where all this happens
         * @param group the group whose buddies have been reordered.
         * @param oldBuddies unused
         * @param newBuddies the list containing the buddies in their new order.
         */
        public void buddiesReordered(BuddyList list, Group group,
                                     List oldBuddies, List newBuddies)
        {
            ContactGroupIcqImpl contactGroup = findContactGroup(group);

            if (contactGroup == null)
            {
                logger.debug(
                    "buddies reordered event received for unknown group"
                    + group);
            }

            List reorderedContacts = new ArrayList();
            Iterator newBuddiesIter = newBuddies.iterator();
            while (newBuddiesIter.hasNext())
            {
                Buddy buddy = (Buddy) newBuddiesIter.next();
                ContactIcqImpl contact = contactGroup.findContact(buddy);

                //make sure that this was not an empty buddy.
                if (contact == null)
                    continue;
                reorderedContacts.add(contact);
            }

            contactGroup.reorderContacts(reorderedContacts);

            fireContactsReordered(contactGroup);
        }

        /**
         * Called by joust sim to indicate that the server stored groups
         * have been reordered. We filter this list for contact groups that
         * we've already heard of and pass it to the root contact group
         * so that it woul reorder its subgroups.
         *
         * @param list the <tt>BuddyList</tt> where all this is happening
         * @param oldOrder unused
         * @param newOrder the order in which groups are now stored by the
         * AIM/ICQ server.
         */
        public void groupsReordered(BuddyList list, List oldOrder,
                                    List newOrder)
        {
            List reorderedGroups = new ArrayList();
            Iterator newOrderIter = newOrder.iterator();
            while (newOrderIter.hasNext())
            {
                Group group = (Group) newOrderIter.next();
                ContactGroupIcqImpl contactGroup = findContactGroup(group);

                //make sure that this was not an empty buddy.
                if (contactGroup == null)
                    continue;
                reorderedGroups.add(contactGroup);
            }

            rootGroup.reorderSubGroups(reorderedGroups);

            fireGroupsReordered();
        }
    }

    /**
     * Proxies events notifying of a change in the group name.
     */
    private class GroupChangeListener
        implements GroupListener
    {
        /**
         * Verifies whether the concerned group really exists and fires
         * a corresponding event
         * @param group the group that changed name.
         * @param oldName the name, before it changed
         * @param newName the current name of the group.
         */
        public void groupNameChanged(Group group, String oldName,
                                     String newName)
        {
            logger.trace("Group name for "+group.getName()+"changed from="
                         + oldName + " to=" + newName);
            ContactGroupIcqImpl contactGroup = findContactGroup(group);

            if (contactGroup == null)
            {
                logger.debug(
                    "group name changed event received for unknown group"
                    + group);
            }

            //check whether the name has really changed (the joust sim stack
            //would call this method even when the name has not really changed
            //and values of oldName and newName would almost always be null)
            if (contactGroup.getGroupName()
                    .equals( contactGroup.getNameCopy() )){
                logger.trace("Group name hasn't really changed("
                             +contactGroup.getGroupName()+"). Ignoring");
                return;
            }

            //we do have a new name. store a copy of it for our next deteciton
            //and fire the corresponding event.
            logger.trace("Dispatching group change event.");
            contactGroup.initNameCopy();

            fireGroupEvent(contactGroup,
                           ServerStoredGroupEvent.GROUP_RENAMED_EVENT);
        }

    }

    private class JoustSimBuddyListener implements BuddyListener
    {
        /**
         * screennameChanged
         *
         * @param buddy Buddy
         * @param oldScreenname Screenname
         * @param newScreenname Screenname
         */
        public void screennameChanged(Buddy buddy, Screenname oldScreenname,
                                      Screenname newScreenname)
        {
            /** @todo implement screennameChanged() */
            logger.debug("/** @todo implement screennameChanged() */=");
            logger.debug("buddy="+buddy);
            System.out.println("oldScreenname=" + oldScreenname);
            System.out.println("newScreenname=" + newScreenname);
        }

        /**
         * alertActionChanged
         *
         * @param buddy Buddy
         * @param oldAlertAction int
         * @param newAlertAction int
         */
        public void alertActionChanged(Buddy buddy, int oldAlertAction,
                                       int newAlertAction)
        {
            /** @todo implement alertActionChanged() */
            logger.debug("/** @todo implement alertActionChanged() */=");
            System.out.println("buddy=" + buddy);
            System.out.println("oldAlertAction=" + oldAlertAction);
            System.out.println("newAlertAction=" + newAlertAction);
        }

        /**
         * alertSoundChanged
         *
         * @param buddy Buddy
         * @param oldAlertSound String
         * @param newAlertSound String
         */
        public void alertSoundChanged(Buddy buddy, String oldAlertSound,
                                      String newAlertSound)
        {
            /** @todo implement alertSoundChanged() */
            logger.debug("/** @todo implement alertSoundChanged() */");
            System.out.println("buddy=" + buddy);
            System.out.println("oldAlertSound=" + oldAlertSound);
            System.out.println("newAlertSound=" + newAlertSound);
        }

        /**
         * alertTimeChanged
         *
         * @param buddy Buddy
         * @param oldAlertEvent int
         * @param newAlertEvent int
         */
        public void alertTimeChanged(Buddy buddy, int oldAlertEvent,
                                     int newAlertEvent)
        {
            /** @todo implement alertTimeChanged() */
            logger.debug("/** @todo implement alertTimeChanged() */");
            System.out.println("buddy=" + buddy);
            System.out.println("oldAlertEvent=" + oldAlertEvent);
            System.out.println("newAlertEvent=" + newAlertEvent);
        }

        /**
         * aliasChanged
         *
         * @param buddy Buddy
         * @param oldAlias String
         * @param newAlias String
         */
        public void aliasChanged(Buddy buddy, String oldAlias, String newAlias)
        {
            /** @todo implement aliasChanged() */
            logger.debug("/** @todo implement aliasChanged() */");
            System.out.println("buddy=" + buddy);
            System.out.println("oldAlias=" + oldAlias);
            System.out.println("newAlias=" + newAlias);
        }

        /**
         * buddyCommentChanged
         *
         * @param buddy Buddy
         * @param oldComment String
         * @param newComment String
         */
        public void buddyCommentChanged(Buddy buddy, String oldComment,
                                        String newComment)
        {
            /** @todo implement buddyCommentChanged() */
            logger.debug("/** @todo implement buddyCommentChanged() */");
            System.out.println("buddy=" + buddy);
            System.out.println("oldComment=" + oldComment);
            System.out.println("newComment=" + newComment);
        }

    }

    /**
     * A dummy implementation of the JoustSIM SsiItemChangeListener.
     *
     * @author Emil Ivov
     */
    private class JoustSimItemChangeListener implements SsiItemChangeListener
    {
        public void handleItemCreated(SsiItem item)
        {
            /** @todo implement handleItemCreated() */
            logger.debug("!!! TODO: implement handleItemCreated() !!!" + item
                         + " DATA=" + item.getData().toString());
        }

        public void handleItemDeleted(SsiItem item)
        {
            /** @todo implement handleItemDeleted() */
            logger.debug("!!! TODO: implement handleItemDeleted()!!!" + item);
        }

        public void handleItemModified(SsiItem item)
        {
            /** @todo implement handleItemModified() */
            logger.debug("!!! TODO: implement handleItemModified() !!!" + item
                         + " DATA=" + item.getData().toString());
        }

    }
}
