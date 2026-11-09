package eu.sytxlabs.tsautocraft.util;

/**
 * Small arithmetic parser for amount fields: numbers (also with a decimal point), {@code + - * /}, {@code x} as times,
 * parentheses and unary minus, e.g. {@code 3*64+12} or {@code (9-2)x16}.
 */
public final class MathExpr {
	private final String s;
	private int pos;

	private MathExpr(String s) {
		this.s = s;
	}

	/** Value of {@code text}, NaN if it is empty or not a valid expression (also on division by zero). */
	public static double eval(String text) {
		MathExpr p = new MathExpr(text.replace(" ", "").replace(',', '.').toLowerCase(java.util.Locale.ROOT));
		if (p.s.isEmpty()) return Double.NaN;
		try {
			double v = p.sum();
			return p.pos == p.s.length() && Double.isFinite(v) ? v : Double.NaN;
		} catch (IllegalArgumentException e) {
			return Double.NaN;
		}
	}

	/** True if {@code c} may appear in an expression (for input filters). */
	public static boolean allowed(char c) {
		return c >= '0' && c <= '9' || "+-*/x().,".indexOf(c) >= 0;
	}

	private double sum() {
		double v = product();
		while (pos < s.length()) {
			char c = s.charAt(pos);
			if (c == '+') {
				pos++;
				v += product();
			} else if (c == '-') {
				pos++;
				v -= product();
			} else {
				break;
			}
		}
		return v;
	}

	private double product() {
		double v = unary();
		while (pos < s.length()) {
			char c = s.charAt(pos);
			if (c == '*' || c == 'x') {
				pos++;
				v *= unary();
			} else if (c == '/') {
				pos++;
				v /= unary();
			} else {
				break;
			}
		}
		return v;
	}

	private double unary() {
		if (pos < s.length() && s.charAt(pos) == '-') {
			pos++;
			return -unary();
		}
		if (pos < s.length() && s.charAt(pos) == '+') {
			pos++;
			return unary();
		}
		return atom();
	}

	private double atom() {
		if (pos < s.length() && s.charAt(pos) == '(') {
			pos++;
			double v = sum();
			if (pos >= s.length() || s.charAt(pos) != ')') throw new IllegalArgumentException();
			pos++;
			return v;
		}
		int start = pos;
		while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
		if (start == pos) throw new IllegalArgumentException();
		return Double.parseDouble(s.substring(start, pos));
	}
}
