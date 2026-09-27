package to.etc.pdp11.web;

import to.etc.domui.component.buttons.DefaultButton;
import to.etc.domui.component.buttons.LinkButton;
import to.etc.domui.component.input.Text2;
import to.etc.domui.component.input.ValueLabelPair;
import to.etc.domui.component.layout.ContentPanel;
import to.etc.domui.component.layout.MessageLine;
import to.etc.domui.component.layout.title.AppPageTitleBar;
import to.etc.domui.component.tbl.DataTable;
import to.etc.domui.component.tbl.RowRenderer;
import to.etc.domui.component.tbl.SimpleListModel;
import to.etc.domui.component2.buttons.ButtonBar2;
import to.etc.domui.component2.combo.ComboFixed2;
import to.etc.domui.component2.form4.FormBuilder;
import to.etc.domui.dom.css.DisplayType;
import to.etc.domui.dom.errors.MsgType;
import to.etc.domui.dom.html.NodeContainer;
import to.etc.domui.dom.html.UrlPage;
import to.etc.pdp11.common.microcode.MicroInstruction;
import to.etc.pdp11.common.microcode.MicrocodeBrowser;
import to.etc.pdp11.common.microcode.MicrocodeBrowser.SearchBy;
import to.etc.pdp11.common.microcode.MicrocodeSource;
import to.etc.pdp11.common.microcode.MicrowordRow;

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

	private final DataTable<MicrowordRow> m_rows = new DataTable<>(new SimpleListModel<>(List.of()), rowRenderer());

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
		m_rows.addCssClass("pdp-rows");
		m_rows.setPreventRowHighlight(true);
		cp.add(m_rows);
		m_status = statusLine();
		cp.add(m_status);

		String upc = getPage().getPageParameters().getString(PARAM_UPC, null);
		show(upc == null ? null : m_browser.searchFor(upc));
	}

	/**
	 * Three columns, the last of which can hold links: where the microword goes next, and each
	 * microword that falls through to it.
	 */
	private RowRenderer<MicrowordRow> rowRenderer() {
		RowRenderer<MicrowordRow> rr = new RowRenderer<>(MicrowordRow.class);
		rr.column("label").label("Field").nowrap();
		rr.column("bits").label("Bits").css("pdp-mono").nowrap();
		rr.column().label("Info").renderer((node, r) -> renderInfo(node, r));
		rr.addRenderListener((tr, r) -> {
			MicrocodeSource other = m_browser.getSource().getOther();
			if(r.differs()) {
				//-- The only way a wrongly chosen board revision ever shows itself.
				tr.addCssClass("pdp-differs");
				if(other != null)
					tr.setTitle("This field is different in " + other.getLabel());
			} else if(r.highlight()) {
				tr.addCssClass("pdp-highlight");
			}
		});
		return rr;
	}

	private void renderInfo(NodeContainer node, MicrowordRow r) {
		if(r.next()) {
			//-- Where it goes next is a link, as a double-click on the row is on the desktop.
			node.add(new LinkButton(r.info(), b -> show(m_browser.next())));
			return;
		}
		List<MicroInstruction> from = m_browser.predecessors();
		if(MicrowordRow.PREDECESSORS.equals(r.label()) && !from.isEmpty()) {
			//-- The one thing a page can do that the window's table cannot: each microword that
			//-- falls through to this one is somewhere to go.
			for(MicroInstruction mi : from) {
				int address = mi.getAddress();
				node.add(new LinkButton(mi.getSymbolicTag() + " (" + mi.getAddressOctal() + ")", b -> {
					m_browser.goTo(address);
					show(null);
				}));
			}
			return;
		}
		node.setText(r.info());
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
		m_rows.setModel(new SimpleListModel<>(m_browser.rows()));

		MessageLine status = statusLine();
		m_status.replaceWith(status);
		m_status = status;
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
