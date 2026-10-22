package se.itssimple.realearth.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import se.itssimple.realearth.mixin.SignBlockEntityAccessor;

/** Names on signs: a shop's over its door, a street's on a post at the kerb. */
final class Signs {
	/** About as many characters as fit on a line of a sign. */
	private static final int LINE = 15;

	private Signs() {
	}

	/**
	 * Writes a name on both faces of a sign, wrapped onto its four lines and centred on them, and waxes
	 * it so it stays as the map has it. Set straight on the block entity: the chunk is still generating.
	 */
	static void write(BlockEntity entity, String name) {
		if (!(entity instanceof SignBlockEntity sign) || name == null || name.isBlank()) {
			return;
		}
		List<String> lines = wrap(name.strip());
		SignText text = new SignText();
		int first = lines.size() >= 3 ? 0 : 1;
		for (int i = 0; i < lines.size() && first + i < 4; i++) {
			text = text.setMessage(first + i, Component.literal(lines.get(i)));
		}
		SignBlockEntityAccessor access = (SignBlockEntityAccessor) sign;
		access.realearth$setFrontText(text);
		access.realearth$setBackText(text);
		access.realearth$setWaxed(true);
	}

	/** Words onto lines of at most LINE characters; a word longer than a line is cut. Four lines at most. */
	static List<String> wrap(String name) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : name.split("\\s+")) {
			if (word.length() > LINE) {
				word = word.substring(0, LINE - 1) + ".";
			}
			if (line.length() > 0 && line.length() + 1 + word.length() > LINE) {
				lines.add(line.toString());
				line.setLength(0);
			}
			if (line.length() > 0) {
				line.append(' ');
			}
			line.append(word);
		}
		if (line.length() > 0) {
			lines.add(line.toString());
		}
		return lines.size() > 4 ? lines.subList(0, 4) : lines;
	}
}
