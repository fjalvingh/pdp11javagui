package to.etc.pdp11.web;

import to.etc.domui.component.buttons.DefaultButton;
import to.etc.domui.component.buttons.LinkButton;
import to.etc.domui.component.input.Text2;
import to.etc.domui.component.input.ValueLabelPair;
import to.etc.domui.component.layout.ContentPanel;
import to.etc.domui.component.layout.MessageLine;
import to.etc.domui.component.layout.title.AppPageTitleBar;
import to.etc.domui.component2.buttons.ButtonBar2;
import to.etc.domui.component2.combo.ComboFixed2;
import to.etc.domui.component2.form4.FormBuilder;
import to.etc.domui.dom.css.DisplayType;
import to.etc.domui.dom.errors.MsgType;
import to.etc.domui.dom.html.Div;
import to.etc.domui.dom.html.HTag;
import to.etc.domui.dom.html.NodeContainer;
import to.etc.domui.dom.html.Span;
import to.etc.domui.dom.html.TBody;
import to.etc.domui.dom.html.TD;
import to.etc.domui.dom.html.TH;
import to.etc.domui.dom.html.TR;
import to.etc.domui.dom.html.Table;
import to.etc.domui.dom.html.UrlPage;
import to.etc.pdp11.common.microcode.MicroInstruction;
import to.etc.pdp11.common.microcode.MicrocodeBrowser;
import to.etc.pdp11.common.microcode.MicrocodeBrowser.SearchBy;
import to.etc.pdp11.common.microcode.MicrocodeSource;
import to.etc.pdp11.common.microcode.MicrowordFieldValue;
import to.etc.pdp11.common.microcode.MicrowordRole;

import java.util.ArrayList;
import java.util.List;

/**
 * A processor's microcode, one microword at a time: the web counterpart of the desktop's
 * Microcode window, and a view over the same {@link MicrocodeBrowser}.
 *
 * <p>Two things differ from the desktop, both because a page is not a window. The document is
 * one of the packaged ones - there is no uploading another scan yet. And the microwords the
 * current one falls through from are links, since a click is how a page gets about.</p>
 *
 * <p>{@code ?source=PDP1105_F&upc=123} opens on a given document and µPC, so a microword can be
 * linked to from anywhere.</p>
 */
public final class MicrocodePage extends UrlPage {
	/** Which document: a {@link MicrocodeSource} constant name. */
	public static final String PARAM_SOURCE = "source";

	/** Which microword to open on, as an octal address. */
	public static final String PARAM_UPC = "upc";

	private final MicrocodeBrowser m_browser = new MicrocodeBrowser(new Slf4jLogger(MicrocodePage.class));

	private final AppPageTitleBar m_titleBar = new AppPageTitleBar("Microcode", false);

	private final ComboFixed2<SearchBy> m_searchBy = new ComboFixed2<>(List.of());

	private final Text2<String> m_search = new Text2<>(String.class);

	private DefaultButton m_back;

	private DefaultButton m_next;

	/** Why the last search or step went nowhere; the line is hidden when it went somewhere. */
	private String m_whyNot;

	private final MessageLine m_message = new MessageLine(MsgType.ERROR, (NodeContainer n) -> n.setText(m_whyNot));

	/** What is on screen and whether the document hangs together; replaced when its type changes. */
	private MessageLine m_status;

	/** The microword, redrawn whenever it changes. */
	private final Div m_view = new Div("pdp-microword");

	@Override
	public void createContent() throws Exception {
		MicrocodeSource source = sourceParameter();
		m_browser.choose(source);

		m_titleBar.setShowBackButton(true);
		add(m_titleBar);
		ContentPanel cp = new ContentPanel();
		add(cp);

		List<ValueLabelPair<MicrocodeSource>> sources = new ArrayList<>();
		for(MicrocodeSource s : MicrocodeSource.values())
			sources.add(new ValueLabelPair<>(s, s.getLabel()));
		ComboFixed2<MicrocodeSource> sourceCombo = new ComboFixed2<>(sources);
		sourceCombo.addCssClass("pdp-source");
		sourceCombo.setMandatory(true);
		sourceCombo.setValue(source);
		sourceCombo.setTitle("The two PDP-11/05 entries are the two M7261 board revisions: read the part"
			+ " numbers off the two control store PROMs to tell which board is in the machine.");
		sourceCombo.setOnValueChanged(c -> {
			MicrocodeSource chosen = sourceCombo.getValue();
			if(chosen == null)
				return;
			m_browser.choose(chosen);
			fillSearchBy();
			show(null);
		});

		m_searchBy.addCssClass("pdp-searchby");
		m_searchBy.setMandatory(true);
		fillSearchBy();
		m_searchBy.setOnValueChanged(c -> {
			m_browser.setSearchBy(m_searchBy.getValue());
			showCurrent();
		});

		m_search.addCssClass("pdp-search");
		m_search.addButton(new DefaultButton("Go", b -> search()));
		m_search.setReturnPressed(n -> search());

		FormBuilder fb = new FormBuilder(cp);
		fb.horizontal();
		fb.label("Microcode").control(sourceCombo);
		fb.label("Search by").control(m_searchBy);
		fb.label("µInstruction").control(m_search);

		ButtonBar2 bar = new ButtonBar2();
		cp.add(bar);
		m_back = bar.addButton("Back", b -> {
			m_browser.back();
			show(null);
		});
		m_next = bar.addButton("Next instruction", b -> show(m_browser.next()));
		m_next.setTitle("Follow this microword's next-address field, which is where it goes when nothing branches");

		m_message.addCssClass("pdp-message");
		cp.add(m_message);
		cp.add(m_view);
		m_status = statusLine();
		cp.add(m_status);

		String upc = getPage().getPageParameters().getString(PARAM_UPC, null);
		show(upc == null ? null : m_browser.searchFor(upc));
	}

	/** The document the URL asks for; the default for none, and for one this version does not have. */
	private MicrocodeSource sourceParameter() {
		String name = getPage().getPageParameters().getString(PARAM_SOURCE, null);
		if(name != null) {
			for(MicrocodeSource s : MicrocodeSource.values()) {
				if(s.name().equals(name))
					return s;
			}
		}
		return MicrocodeSource.DEFAULT;
	}

	/** The search orders this document supports: the KD11-B transcription has no line numbers. */
	private void fillSearchBy() {
		List<ValueLabelPair<SearchBy>> items = new ArrayList<>();
		for(SearchBy by : SearchBy.availableFor(m_browser.getSource()))
			items.add(new ValueLabelPair<>(by, by.getLabel()));
		m_searchBy.setData(items);
		m_searchBy.setValue(m_browser.getSearchBy());
	}

	private void search() {
		show(m_browser.searchFor(m_search.getValue()));
	}

	/** Show where a move went, or - leaving what is on screen alone - why it could not go. */
	private void show(String whyNot) {
		m_whyNot = whyNot;
		m_message.setDisplay(whyNot == null ? DisplayType.NONE : null);
		m_message.forceRebuild();
		if(whyNot == null)
			showCurrent();
	}

	private void showCurrent() {
		MicroInstruction mi = m_browser.getCurrent();
		String title = "Microcode - " + m_browser.getSource().getLabel();
		m_titleBar.setPageTitle(title);
		setPageTitle(title);
		m_search.setValue(mi == null ? null : m_browser.searchTextOf(mi));
		m_back.setDisabled(!m_browser.canGoBack());
		m_next.setDisabled(!m_browser.canGoNext());
		m_view.removeAllChildren();
		if(mi != null)
			renderMicroword(mi);

		MessageLine status = statusLine();
		m_status.replaceWith(status);
		m_status = status;
	}

	// -------------------------------------------------------------------------------------
	// The microword
	// -------------------------------------------------------------------------------------

	/**
	 * One microword, in the sections its rows naturally fall into: what it is for, where it goes,
	 * its source (where the document has any), its fields, the notes on it, and where it was read.
	 *
	 * <p>A hand-built table rather than a DataTable, because the rows are not alike: a field has a
	 * bit range, a value and a meaning, a note is a line of prose, and a flow row is a set of
	 * links. Each section is drawn the way its kind of row reads best.</p>
	 */
	private void renderMicroword(MicroInstruction mi) {
		HTag title = new HTag(2);
		title.addCssClass("pdp-mw-title");
		title.add(new Span("pdp-mw-tag", mi.getSymbolicTag()));
		title.add(new Span("pdp-mw-upc", "µPC " + mi.getAddressOctal()));
		m_view.add(title);

		//-- What it is for comes first: of everything here it is what a reader wants first.
		MicrowordRole role = m_browser.role();
		if(role != null && role.summary() != null)
			m_view.add(new Div("pdp-mw-part", role.summary()));
		if(role != null && role.action() != null)
			m_view.add(new Div("pdp-mw-does", role.action()));

		Table table = new Table("pdp-mw");
		m_view.add(table);
		renderFlow(table, mi);
		if(!mi.getOperations().isEmpty()) {
			//-- The microassembler source, one operation a line. The KD11-B transcription is a bit
			//-- table and has none, and then there is no such section rather than an empty one.
			TBody body = section(table, "Source code");
			for(String op : mi.getOperations())
				wide(body, "pdp-mw-code").setText(op);
		}
		renderFields(table, mi);
		if(role != null && !role.notes().isEmpty()) {
			//-- One line per comment, as printed: some microwords have a dozen - B2-2 is where every
			//-- instruction ends - and one cell holding them all is a cell nobody can read.
			TBody body = section(table, "Notes");
			for(String note : role.notes())
				wide(body, "pdp-mw-note").setText(note);
		}
		TBody from = section(table, "Read from");
		pair(from, "Listing", mi.getSourceName() + ", line " + mi.getLineNumber());
		if(role != null && role.source() != null)
			pair(from, "Flow", role.source());

		renderLegend();
	}

	/** Where the microword goes, and what comes to it - both of them somewhere to go. */
	private void renderFlow(Table table, MicroInstruction mi) {
		TBody body = section(table, "Flow");
		TD next = pair(body, "Next", null);
		next.add(new LinkButton(mi.getNextAddressOctal(), b -> show(m_browser.next())));
		//-- Where a microtest is selected the hardware ORs its result into the next address, so
		//-- what is printed is a branch base and not the successor; saying "next 162" flat would
		//-- state as fact something that depends on the state of the machine.
		if(mi.isBranching())
			next.add(new Span("pdp-mw-aside", "a branch base: the " + mi.getMicrotestName()
				+ " microtest replaces some of these bits with what it finds"));
		if(m_browser.getMicrocode().nextNotInDocument(mi))
			next.add(new Span("pdp-mw-aside", "not printed in this document"));

		TD reached = pair(body, "Reached from", null);
		List<MicroInstruction> predecessors = m_browser.predecessors();
		if(predecessors.isEmpty()) {
			//-- Normal, and not the same as unreachable: the interesting microwords are the ones a
			//-- branch lands on, and a branch target is chosen by hardware substituting bits into
			//-- the next address, which no listing spells out.
			reached.add(new Span("pdp-mw-aside", "nothing falls through to it - it is reached by a branch,"
				+ " or it is a starting point"));
			return;
		}
		for(MicroInstruction p : predecessors) {
			int address = p.getAddress();
			reached.add(new LinkButton(p.getSymbolicTag() + " (" + p.getAddressOctal() + ")", b -> {
				m_browser.goTo(address);
				show(null);
			}));
		}
	}

	/** Every field: name, bits, value, and what the value means or why it does not matter. */
	private void renderFields(Table table, MicroInstruction mi) {
		TBody body = section(table, "Fields");
		TR head = body.addRow("pdp-mw-colhead");
		for(String h : new String[]{"Field", "Bits", "Value", "Meaning"}) {
			TH th = new TH();
			th.setText(h);
			head.add(th);
		}
		MicrocodeSource other = m_browser.getSource().getOther();
		for(MicrowordFieldValue v : m_browser.fieldValues()) {
			TR tr = body.addRow("pdp-mw-field");
			if(v.differs())
				tr.addCssClass("pdp-differs");
			else if(v.active())
				tr.addCssClass("pdp-active");

			//-- The KD11-B names its fields "SPA (ROM-SPA-3..0-H)": the mnemonic, then the signals
			//-- on the print set. Set apart, the mnemonic is what the eye finds.
			TD name = tr.addCell("pdp-mw-name");
			String n = v.field().name();
			int paren = n.indexOf(" (");
			if(paren > 0 && n.endsWith(")")) {
				name.add(new Span("pdp-mw-mnemonic", n.substring(0, paren)));
				name.add(new Span("pdp-mw-signal", n.substring(paren + 2, n.length() - 1)));
			} else {
				name.add(new Span("pdp-mw-mnemonic", n));
			}
			tr.addCell("pdp-mono pdp-mw-bits").setText(v.field().bitRange());
			tr.addCell("pdp-mono pdp-mw-value").setText(v.octal());

			TD meaning = tr.addCell("pdp-mw-meaning");
			if(v.meaning() != null)
				meaning.add(new Span(v.meaning()));
			if(v.dontCare() != null)
				meaning.add(new Span("pdp-mw-aside", "don't care: " + v.dontCare()));
			if(v.nextAddress())
				meaning.add(new Span("pdp-mw-aside", "where it goes next; see Flow"));
			if(v.differs() && other != null) {
				//-- The only way a wrongly chosen board revision ever shows itself, so say what the
				//-- other one has: that is what tells somebody holding the board which it is.
				MicrowordFieldValue o = m_browser.otherRevisionField(v.field());
				if(o != null)
					meaning.add(new Span("pdp-mw-other", other.getLabel() + " has " + o.info()));
			}
		}
	}

	/** What the two colours mean, and the second only where there is another revision to differ from. */
	private void renderLegend() {
		Div legend = new Div("pdp-mw-legend");
		legend.add(new Span("pdp-swatch pdp-active", ""));
		legend.add(new Span("doing something this cycle"));
		MicrocodeSource other = m_browser.getSource().getOther();
		if(other != null) {
			legend.add(new Span("pdp-swatch pdp-differs", ""));
			legend.add(new Span("different in " + other.getLabel()));
		}
		m_view.add(legend);
	}

	/** A section of the table, headed by its name across the full width. */
	private static TBody section(Table table, String name) {
		TBody body = new TBody();
		table.add(body);
		TR tr = body.addRow("pdp-mw-section");
		TH th = new TH();
		th.setColspan(4);
		th.setText(name);
		tr.add(th);
		return body;
	}

	/** A label and a value spanning the rest of the row; the value cell is returned to fill. */
	private static TD pair(TBody body, String label, String value) {
		TR tr = body.addRow("pdp-mw-pair");
		tr.addCell("pdp-mw-label").setText(label);
		TD td = tr.addCell();
		td.setColspan(3);
		if(value != null)
			td.setText(value);
		return td;
	}

	/** A row that is a single cell across the full width. */
	private static TD wide(TBody body, String css) {
		TR tr = body.addRow();
		TD td = tr.addCell(css);
		td.setColspan(4);
		return td;
	}

	/**
	 * A warning when the document did not read cleanly or the microword leaves it, information
	 * otherwise. The text is set as text, not as HTML, since a search the user typed ends up in it.
	 */
	private MessageLine statusLine() {
		String text = m_browser.statusText();
		MessageLine line = new MessageLine(m_browser.isStatusTroubled() ? MsgType.WARNING : MsgType.INFO,
			(NodeContainer n) -> n.setText(text));
		line.addCssClass("pdp-status");
		return line;
	}
}
