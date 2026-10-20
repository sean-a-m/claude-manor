* Investigate parallel, or at least somewhat parallel, move systems - hypothetical for now, but will be desirable once we have large numbers of agents. Might want to let everything attempt to go in paralell and then resolve conflicts somehow. If time budgets are implmeenting, could let agents resolve conversation within turn without blocking other actions from other agents for instance
* ensure good separation of data from code. It's lisp so it's fine if it's all .clj files, but the basic game logic shouldn't be intermingled with details about particular items or the layout of claudeworth. If it's seperate it's also easier for the user to adjust simulation parameters later.
* Various items inside items concerns - size, locking, etc
rejected: drawing-room is neither here nor carried by you; items here: china-cabinet (China cabinet), meissen-harlequin (Meissen harlequin), meissen-pug (Meissen pug), meissen-shepherdess (Meissen shepherdess), piano (Piano); you carry: nothing
arguments as sent
* Add more locations in the village, repair, grocer, fabric shops, whatever would exist historically. should be fast with hibernating shopkeepers
* delivery
* add whispering conversations
* Generate new goals at times - for instance, based on NPC characteristics, they may have minor goals to engage in certain acts if they are religious or talkative or love to gamble (like going to pray or buying a new set or rosary beads, or having a conversation with a new person, or getting a poker game going below stairs). Possibly also larger plot driven goals that could be added to mix things up.
* 