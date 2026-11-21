package dev.trademaster.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McNbtContainsTest {

	@Test
	void configuredFieldsMustBePresentButExtraFieldsAreAllowed() {
		var live = McNbt.parse("{display:{Name:'\"Excalibur\"',Lore:['a','b']},Custom:1}");
		var filter = McNbt.parse("{display:{Name:'\"Excalibur\"'}}");
		assertNotNull(live);
		assertNotNull(filter);
		assertTrue(McNbt.contains(live, filter));
		assertFalse(McNbt.contains(live, McNbt.parse("{display:{Name:'\"Other\"'}}")));
		assertFalse(McNbt.contains(live, McNbt.parse("{Missing:1}")));
	}

	@Test
	void listsMatchConfiguredElementsAndAllowExtras() {
		var live = McNbt.parse("{Enchantments:[{id:'minecraft:sharpness',lvl:5},{id:'minecraft:mending',lvl:1}]}");
		var filter = McNbt.parse("{Enchantments:[{id:'minecraft:sharpness',lvl:5}]}");
		assertNotNull(live);
		assertNotNull(filter);
		assertTrue(McNbt.contains(live, filter));
		assertFalse(McNbt.contains(live, McNbt.parse("{Enchantments:[{id:'minecraft:sharpness',lvl:6}]}")));
	}

	@Test
	void numericWidthsCompareByValue() {
		var live = McNbt.parse("{Damage:5s,Other:1b}");
		var filter = McNbt.parse("{Damage:5}");
		assertNotNull(live);
		assertNotNull(filter);
		assertTrue(McNbt.contains(live, filter));
		assertFalse(McNbt.contains(live, McNbt.parse("{Damage:6}")));
	}

	@Test
	void blankAndInvalidTextBehaviour() {
		assertTrue(McNbt.valid(""));
		assertTrue(McNbt.valid("{Custom:1}"));
		assertFalse(McNbt.valid("{not a compound"));
		assertNull(McNbt.parse("{not a compound"));
	}
}
