package com.indevsolutions.workshop.play.service;

import static com.indevsolutions.workshop.play.service.Error.BET_CLOSED;
import static com.indevsolutions.workshop.play.service.Error.BET_NOT_VALID;
import static com.indevsolutions.workshop.play.service.Error.BET_NOT_VALID_MAX;
import static com.indevsolutions.workshop.play.service.Error.BET_NOT_VALID_MIN;
import static com.indevsolutions.workshop.play.service.Error.CHOICE_NOT_VALID;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.ObjectUtils;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.server.ResponseStatusException;

import com.indevsolutions.workshop.play.domain.Play;
import com.indevsolutions.workshop.play.dto.PlayBetDTO;
import com.indevsolutions.workshop.play.dto.PlayChoiceDTO;
import com.indevsolutions.workshop.play.dto.PlaySummaryDTO;
import com.indevsolutions.workshop.play.dto.shared.BetDTO;
import com.indevsolutions.workshop.play.dto.shared.BetOptionDTO;
import com.indevsolutions.workshop.play.repository.PlayRepository;

@Service
public class PlayService {

	private static final long MINUTES_BEFORE_CLOSE_BET = 10;

	private final PlayRepository playRepository;
	private final BetService betService;
	private final ModelMapper modelMapper;
	private final MessageService messageService;

	public PlayService(PlayRepository playRepository, BetService betService, ModelMapper modelMapper,
			MessageService messageService) {
		super();
		this.playRepository = playRepository;
		this.betService = betService;
		this.modelMapper = modelMapper;
		this.messageService = messageService;
	}

	/**
	 * Returns the latest 5 plays.
	 * 
	 * @param userId
	 * @return
	 */
	public List<PlaySummaryDTO> findLatestPlays(Long userId) {
		var plays = playRepository.findTop5ByUserIdOrderByRegistrationDateDesc(userId);
		var betIds = plays.stream().map(Play::getBetId).collect(Collectors.toSet());
		var bets = betService.findBetsByIds(betIds).stream().collect(Collectors.toMap(BetDTO::getId, b -> b));

		return plays.stream().map(p -> {
			var play = modelMapper.map(p, PlaySummaryDTO.class);

			var bet = bets.get(p.getBetId());
			if (bet != null) {
				var playBet = modelMapper.map(bet, PlayBetDTO.class);
				play.setBet(playBet);

				getBetOption(bet, p.getChoiceId()).map(o -> modelMapper.map(o, PlayChoiceDTO.class))
						.ifPresent(play::setChoice);

				getBetOption(bet, bet.getResultId()).map(BetOptionDTO::getDescription).ifPresent(play::setResult);
			}

			return play;
		}).toList();
	}

	/**
	 * Returns the bet option based on the given id.
	 * 
	 * @param bet
	 * @param id
	 * @return
	 */
	private Optional<BetOptionDTO> getBetOption(BetDTO bet, Long id) {
		if (id == null) {
			return Optional.empty();
		}

		return bet.getOptions().stream().filter(o -> Objects.equals(o.getId(), id)).findFirst();
	}

	/**
	 * Creates a play based on the given data.
	 * 
	 * @param play
	 * @return
	 */
	public Play createPlay(Play play) {
		var bets = betService.findBetsByIds(Set.of(play.getBetId()));

		if (CollectionUtils.isEmpty(bets)) {
			throw new ResponseStatusException(BAD_REQUEST, messageService.getMessage(BET_NOT_VALID));
		}

		var bet = bets.get(0);
		if (ObjectUtils.compare(bet.getMinAmount(), play.getAmount()) > 0) {
			throw new ResponseStatusException(BAD_REQUEST, messageService.getMessage(BET_NOT_VALID_MIN));
		}

		if (ObjectUtils.compare(bet.getMaxAmount(), play.getAmount(), true) < 0) {
			throw new ResponseStatusException(BAD_REQUEST, messageService.getMessage(BET_NOT_VALID_MAX));
		}

		var isOptionValid = bet.getOptions().stream().anyMatch(o -> o.getId().equals(play.getChoiceId()));
		if (!isOptionValid) {
			throw new ResponseStatusException(BAD_REQUEST, messageService.getMessage(CHOICE_NOT_VALID));
		}

		var now = LocalDateTime.now();
		var duration = Duration.between(now, bet.getMatchDate());

		if (duration.toMinutes() <= MINUTES_BEFORE_CLOSE_BET) {
			throw new ResponseStatusException(BAD_REQUEST, messageService.getMessage(BET_CLOSED));
		}

		play.setRegistrationDate(now);
		return playRepository.save(play);
	}
}
