package dev.trademaster.config;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Namespaceless / case-insensitive / garbage handling of the Villagers tab ids. */
class ConfigIdentifierTest {

	private String workBlock;
	private String profession;

	@BeforeEach
	void capture() {
		workBlock = TradeMasterConfig.INSTANCE.workBlockId;
		profession = TradeMasterConfig.INSTANCE.professionId;
	}

	@AfterEach
	void restore() {
		TradeMasterConfig.INSTANCE.workBlockId = workBlock;
		TradeMasterConfig.INSTANCE.professionId = profession;
	}

	@Test
	void defaultsResolve() {
		TradeMasterConfig.INSTANCE.workBlockId = "minecraft:lectern";
		TradeMasterConfig.INSTANCE.professionId = "minecraft:librarian";
		assertEquals(Identifier.withDefaultNamespace("lectern"), TradeMasterConfig.INSTANCE.workBlockIdentifier());
		assertEquals(Identifier.withDefaultNamespace("librarian"), TradeMasterConfig.INSTANCE.professionIdentifier());
	}

	@Test
	void namespacelessMeansMinecraft() {
		TradeMasterConfig.INSTANCE.workBlockId = "composter";
		assertEquals(Identifier.withDefaultNamespace("composter"), TradeMasterConfig.INSTANCE.workBlockIdentifier());
	}

	@Test
	void caseIsNormalised() {
		TradeMasterConfig.INSTANCE.workBlockId = "  COMPOSTER ";
		assertEquals(Identifier.withDefaultNamespace("composter"), TradeMasterConfig.INSTANCE.workBlockIdentifier());
		TradeMasterConfig.INSTANCE.professionId = "MINECRAFT:Farmer";
		assertEquals(Identifier.withDefaultNamespace("farmer"), TradeMasterConfig.INSTANCE.professionIdentifier());
	}

	@Test
	void otherNamespacesKeepTheirNamespace() {
		TradeMasterConfig.INSTANCE.workBlockId = "some_mod:machine";
		assertEquals(Identifier.fromNamespaceAndPath("some_mod", "machine"),
				TradeMasterConfig.INSTANCE.workBlockIdentifier());
	}

	@Test
	void blankOrGarbageIsNull() {
		TradeMasterConfig.INSTANCE.workBlockId = "";
		assertNull(TradeMasterConfig.INSTANCE.workBlockIdentifier());
		TradeMasterConfig.INSTANCE.workBlockId = "   ";
		assertNull(TradeMasterConfig.INSTANCE.workBlockIdentifier());
		TradeMasterConfig.INSTANCE.workBlockId = "!!!";
		assertNull(TradeMasterConfig.INSTANCE.workBlockIdentifier());
		TradeMasterConfig.INSTANCE.workBlockId = "minecraft:!!!";
		assertNull(TradeMasterConfig.INSTANCE.workBlockIdentifier());
		TradeMasterConfig.INSTANCE.professionId = "  ";
		assertNull(TradeMasterConfig.INSTANCE.professionIdentifier());
	}
}
