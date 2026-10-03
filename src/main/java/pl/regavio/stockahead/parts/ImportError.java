package pl.regavio.stockahead.parts;

import java.io.Serializable;
import java.util.List;

/**
 * One problem found in an uploaded parts CSV: the bundle key of its Polish
 * message and that message's arguments. {@code rowNumber} is the Excel row
 * the problem belongs to (the header is row 1), or {@link #FILE_LEVEL} for a
 * problem with the file as a whole. When the message names the row, the row
 * number is also its first argument ({@code {0}}).
 */
record ImportError(int rowNumber, String messageKey, List<Object> args) implements Serializable {

	static final int FILE_LEVEL = 0;

	ImportError {
		args = List.copyOf(args);
	}

	static ImportError ofFile(String messageKey, Object... args) {
		return new ImportError(FILE_LEVEL, messageKey, List.of(args));
	}

	/** An error whose message starts with the row number as {@code {0}}. */
	static ImportError ofRow(int rowNumber, String messageKey, Object... extraArgs) {
		Object[] args = new Object[extraArgs.length + 1];
		args[0] = rowNumber;
		System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
		return new ImportError(rowNumber, messageKey, List.of(args));
	}

	Object[] argsArray() {
		return args.toArray();
	}

}
